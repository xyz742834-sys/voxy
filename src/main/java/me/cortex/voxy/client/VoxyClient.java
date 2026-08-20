package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;

    /** どの描画バックエンドで動くか。 */
    public enum Backend {
        /** 従来の OpenGL 経路。 */
        OPENGL,
        /**
         * Vulkan / MoltenVK 経路 (Phase 5)。<b>GL が要件を満たさないときだけ選ばれる。</b>
         * 描画結果は IOSurface 経由で GL のフレームバッファに合成する。
         */
        VULKAN
    }

    private static Backend BACKEND = null;

    /**
     * 使用するバックエンド。{@link #initVoxyClient} の後にだけ意味がある。
     * Voxy が無効なら null。
     */
    public static Backend backend() { return BACKEND; }

    /**
     * <b>Vulkan が実際に使えるかを、初期化まで行って確かめる。</b>
     *
     * <p>capability の有無だけで判定すると、拡張はあるのに
     * {@code vkCreateDevice} が失敗する環境で
     * <b>「対応しているのに動かない」</b>状態になる。
     * <b>実際に使える状態まで確認してから通す。</b>
     *
     * <p>{@link me.cortex.voxy.client.core.vk.VkContext} はサーフェスもウィンドウも持たないので、
     * ここ (Render thread、{@code RenderSystem.initRenderer} の中) で初期化してよい
     * [確認済 — 素の JVM のテストが同じことをしている]。
     */
    private static boolean vulkanIsUsable() {
        try {
            me.cortex.voxy.client.core.vk.VkContext.init();
            return true;
        } catch (Throwable t) {
            Logger.warn("Vulkan backend is not usable: " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            return false;
        }
    }

    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        // GL 経路の要件。Apple の GL 4.1 には glDispatchComputeIndirect も
        // glMultiDrawElementsIndirectCountARB も無いので、macOS では必ず false になる
        // [確認済 — docs/phase2-binding-audit.md 8.4]
        boolean glSupported = Capabilities.INSTANCE.compute
                && Capabilities.INSTANCE.indirectParameters
                && !Capabilities.INSTANCE.hasBrokenDepthSampler;

        // ⚠ Vulkan 経路も **GL コンテキストを必要とする**。
        // 描いた結果を IOSurface 経由で GL のフレームバッファに合成し、
        // MC の色/深度テクスチャも Sodium 経由で **GL のテクスチャ ID** として受け取るためである
        // [確認済 — MixinDefaultChunkRenderer が GlTextureView にキャストしている]。
        //
        // したがって **MC 自身が Vulkan バックエンドで動いている場合は Voxy は動かせない**。
        // ここを見落とすと、Voxy が有効になった後で
        // VulkanGpuTextureView -> GlTextureView のキャストに失敗して
        // **描画フレームでクラッシュする** [確認済 — 実機で発生させた。
        // docs/phase5c-mc-vulkan-survey.md 4.2b]。
        boolean mcIsOnOpenGl = Capabilities.INSTANCE.glAvailable;
        if (!mcIsOnOpenGl) {
            Logger.error("Minecraft is not using the OpenGL backend; Voxy's Vulkan path still "
                + "needs a GL context to composite through. Voxy will disable itself. "
                + "(set preferredGraphicsBackend to \"default\" in options.txt)");
        }

        // GL が使えるならそちらを優先する。Vulkan は GL が足りないときの受け皿であって、
        // 置き換えではない (将来 GL 4.6 環境で動かす余地を残す)
        if (glSupported) {
            BACKEND = Backend.OPENGL;
        } else if (mcIsOnOpenGl && vulkanIsUsable()) {
            BACKEND = Backend.VULKAN;
            Logger.info("OpenGL does not meet Voxy's requirements; using the Vulkan backend.");
        } else {
            BACKEND = null;
        }

        boolean systemSupported = BACKEND != null;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            if (BACKEND == Backend.OPENGL) {
                // ⚠ GL 経路専用の先行確保。
                // GlBuffer は glCreateBuffers (GL 4.5 DSA) を使うので、
                // Apple の GL 4.1 で呼ぶと **JVM ごと abort する**
                // ("FATAL ERROR in native method: ... a function that is not available")。
                // Vulkan 経路では VkQuadIndexBuffer が同じ役割を持つ
                SharedIndexBuffer.INSTANCE.id();

                if (!Capabilities.INSTANCE.subgroup) {
                    Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
                }
            }

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
        }
    }

    @Override
    public void onInitializeClient() {
        DebugEntries.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register());
            }
        });

        FabricLoader.getInstance()
                .getEntrypoints("frex_flawless_frames", Consumer.class)
                .forEach(api -> ((Consumer<Function<String,Consumer<Boolean>>>)api).accept(name->active->{if (active) {
                    FREX.add(name);
                } else {
                    FREX.remove(name);
                }}));
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}