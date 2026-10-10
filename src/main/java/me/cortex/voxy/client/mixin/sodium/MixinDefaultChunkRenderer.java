package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.vk.interop.VkInteropProbe;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(ChunkVertexType vertexType) {
        super(vertexType);
    }

    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass, parameters, terrainSampler);
            this.doRender(matrices, renderPass, camera, parameters);
            super.end(renderPass);
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void voxy$injectRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera, parameters);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer == null && VoxyClient.nativeInstanceMode()) {
                // native instance mode (experimental, flagged): copy Minecraft's own matrices
                // and camera for the native probes at the level-render tail. No GL, no draw.
                var target = renderPass.getTarget();
                // and Minecraft's raw camera projection, which Voxy's own projection factors out
                // (VoxyRenderSystem.computeProjectionMat)
                me.cortex.voxy.client.core.vk.mcnative.McNativeCamera.capture(
                    matrices.projection(), net.minecraft.client.Minecraft.getInstance().gameRenderer
                        .gameRenderState().levelRenderState.cameraRenderState.projectionMatrix,
                    matrices.modelView(), camera.x, camera.y, camera.z,
                    target.width, target.height);
                // and Minecraft's fog, as GL Voxy's viewport keeps it for its final blit
                me.cortex.voxy.client.core.vk.mcnative.McNativeFog.capture(fogParameters);
                // and draw here, at GL Voxy's point: no render pass is open before Sodium's end()
                me.cortex.voxy.client.core.vk.mcnative.McNativeFrameHooks.atCutout();
                return;
            }
            if (renderer == null && VoxyClient.backend() == VoxyClient.Backend.VULKAN) {
                // Phase 5c-1d: **Phase 4 の合成地形**を Vulkan で描いて合成する。
                // ここで初めて Voxy 自身の深度を MC の深度バッファへ書くので、
                // 深度テストの設定が MC の描画順と噛み合うかが焦点になる
                // [docs/phase5c1d-completion.md]
                var target = renderPass.getTarget();
                VkInteropProbe.get().composite(
                    ((GlTextureView) target.getColorTextureView()).glId(),
                    ((GlTextureView) target.getDepthTextureView()).glId(),
                    target.width, target.height,
                    // 5c-1d: 合成地形を **MC の投影**で描く。座標系の変換を増やさない
                    matrices.projection(), matrices.modelView(),
                    camera.x, camera.y, camera.z);
                return;
            }
            if (renderer != null) {
                Viewport<?> viewport = null;
                var target = renderPass.getTarget();
                if (IrisUtil.USED_IRIS_VIEWPORT) {
                    viewport = renderer.getViewport();
                    IrisUtil.USED_IRIS_VIEWPORT = false;
                } else {
                    viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParameters, target.width, target.height, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport, ((GlTextureView)target.getDepthTextureView()).glId(), ((GlTextureView)target.getColorTextureView()).glId());
            }
        }
    }
}
