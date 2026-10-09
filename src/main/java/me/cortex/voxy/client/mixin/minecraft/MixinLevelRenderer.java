package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IVoxyRenderSystemHolder {
    @Unique @Nullable private WorldIdentifier identifier;
    @Unique private @Nullable VoxyRenderSystem renderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
        me.cortex.voxy.client.core.vk.mcnative.McNativeDepthLadder.shutdown();
        me.cortex.voxy.client.core.vk.mcnative.McNativeTerrainLoad.shutdown();
        me.cortex.voxy.client.core.vk.mcnative.McNativeTerrainProbe.shutdown();
        me.cortex.voxy.client.core.vk.mcnative.McNativeMarkerDraw.shutdown();
    }

    /**
     * ⚠ レベル描画の<b>末尾</b>で、MC が開いたレンダーパスがすべて閉じた後に呼ぶ。
     * ここで初めて自前のパスを開けるし、MC のコマンドバッファへ積んでも
     * パスの入れ子にならない。既定では何もしない
     * ({@code -Dvoxy.native.marker=true} のときだけ描く)
     * [docs/ai/vulkan-native-integration-survey.md]。
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void voxy$injectNativeMarker(CallbackInfo ci) {
        // ⚠ 地形 probe を<b>先に</b>呼ぶ。有効なときは MC の colour/depth をクリアするので、
        // 後に呼ぶとマーカーを消してしまい、マーカー側の測定が成り立たなくなる。
        // どちらも既定では何もしない。
        // ⚠ 深度 probe は<b>いちばん先</b>に、まだ誰も上書きしていない MC のシーン深度を読む。
        // 読み戻すだけで何も書かない。既定では何もしない。
        me.cortex.voxy.client.core.vk.mcnative.McNativeDepthProbe.probeOnce();
        // ⚠ 深度の梯子は MC のシーン深度に対して<b>テストだけ</b>する (深度は書かない)。
        // terrain probe より先に呼ぶ: あちらは有効なとき MC の深度をクリアするので、
        // 後に呼ぶと測る対象が消える。既定ではどちらも何もしない。
        me.cortex.voxy.client.core.vk.mcnative.McNativeDepthLadder.renderIfEnabled();
        // ⚠ terrain-LOAD は梯子の<b>後</b>: 梯子が同じフレームで読み戻しを要求した後に、
        // 梯子の帯へ Voxy の地形を LOAD パスで描いて深度も書く (梯子自身は決して書かない)。
        // 既定では何もしない。
        me.cortex.voxy.client.core.vk.mcnative.McNativeTerrainLoad.renderIfEnabled();
        me.cortex.voxy.client.core.vk.mcnative.McNativeTerrainProbe.renderIfEnabled();
        me.cortex.voxy.client.core.vk.mcnative.McNativeMarkerDraw.renderIfEnabled();
    }

    @Override
    public void voxy$shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    /*
    @Override
    public void voxy$reloadRenderer() {
        this.voxy$shutdownRenderer();
        this.voxy$createRenderer();
    }*/

    @Override
    public void voxy$setWorld(Level level) {
        WorldIdentifier identifier = level==null?null:WorldIdentifier.of(level);
        if (Objects.equals(this.identifier, identifier)) return;
        this.voxy$shutdownRenderer();
        this.identifier = identifier;
    }

    @Override
    public void voxy$createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (VoxyClient.backend() == VoxyClient.Backend.VULKAN) {
            // Phase 5c-1a: バックエンド選択までは通し、描画系はまだ作らない。
            //
            // VoxyRenderSystem のコンストラクタは GL を直接叩く
            // (glFinish / glGetIntegeri / RenderResourceReuse の GL バッファ)。
            // この Mac の GL 4.1 では compute も MDI も無いので、作れば必ず落ちる。
            // **作らないことで「MC の絵が一切変わらない」状態を保つ**
            // [docs/phase5c-plan.md 9 の 5c-1a]。
            Logger.info("Not creating renderer: the Vulkan backend is selected but the render "
                + "path is not wired yet (Phase 5c-1a). Minecraft renders unchanged.");
            return;
        }
        if (this.identifier == null) {
            Logger.info("Not creating renderer due to null identifier");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            //This is now legal (e.g. when the instance is disabled)
            Logger.info("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = this.identifier.getOrCreateEngine(true);
        if (world == null) {
            Logger.warn("Not creating renderer due to null engine");
            return;
        }
        this.voxy$createEngineDirect(world);
    }

    @Unique
    private void voxy$createEngineDirect(WorldEngine world) {
        var instance = world.instanceIn;
        if (instance == null) throw new IllegalStateException();//in theory this could be null if is like in a test suit or something
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                IrisUtil.disableIrisShaders();
            } else {
                throw e;
            }
        }
        instance.updateDedicatedThreads();
    }
}
