package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class SoftwareModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1/*has discard*/);
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);

    private final FluidRenderer fr;
    public SoftwareModelTextureBakery() {
        this.fr = new FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
    }

    /**
     * Block-atlas pixels supplied from outside GL (EXPERIMENTAL, native instance mode only).
     *
     * <p>On Minecraft's Vulkan backend there is no GL context, so the raw-GL read below aborts
     * the JVM ("No context is current", measured 2026-10-09). The native real-section experiment
     * reads the atlas through Minecraft's own Blaze3D API ({@code McNativeAtlas}) and supplies the
     * pixels here — same layout as the GL read: mip 0, RGBA8 bytes as little-endian ints.
     * When unset, the GL path is used exactly as before.
     */
    public record AtlasPixels(int[] pixels, int width, int height) {}
    private static volatile AtlasPixels suppliedAtlas;
    public static void supplyAtlas(AtlasPixels atlas) { suppliedAtlas = atlas; }

    public void setupTexture() {
        var tex = Minecraft.getInstance().getTextureManager().getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")).getTexture();
        if (tex.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Block atlas not rgba8: " + tex.getFormat());
        }
        var supplied = suppliedAtlas;
        if (supplied != null) {
            if (supplied.width() != tex.getWidth(0) || supplied.height() != tex.getHeight(0)) {
                throw new IllegalStateException("the supplied block atlas is " + supplied.width() + "x"
                        + supplied.height() + " but the atlas is " + tex.getWidth(0) + "x" + tex.getHeight(0));
            }
            this.useAtlas(supplied.pixels(), supplied.width(), supplied.height());
            return;
        }

        int targetMipLevel = 0;// Math.min(tex.getMipLevels(), 4)-1;//todo: we want to target the mip layer that has the 16x16 sized textures

        int width = tex.getWidth(targetMipLevel);
        int height = tex.getHeight(targetMipLevel);

        //Just do it ourselves as doing it with b3d has some issues, (doing it ourselves is also just much much much shorter)
        var texture = new int[width * height];

        // ⚠ ここから**生の GL で MC の状態を変える**。Blaze3D の GlStateManager は
        // 控えを持っているので、変えたまま戻さないと**控えと実際がずれる**
        // [5c-1b の黒画面]。上流は起動時に 1 度だけ呼んでいたので問題にならなかったが、
        // Vulkan 経路では**フレームの途中** (DefaultChunkRenderer.doRender の中) で
        // 呼ばれるので、MC の描画先を奪ったままにすると**そのフレームが壊れる**。
        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevPackBuf = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int prevPackRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int prevPackImageHeight = glGetInteger(GL_PACK_IMAGE_HEIGHT);
        int prevPackSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int prevPackSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        int prevPackAlignment = glGetInteger(GL_PACK_ALIGNMENT);

        glFlush();
        glFinish();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_PACK_ROW_LENGTH, width);
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        // ⚠ glGetTextureImage は **GL 4.5 (DSA)** である。Apple の GL 4.1 には無く、
        // 関数ポインタが NULL なので LWJGL が NullPointerException を投げる
        // [確認済 — run/crash-reports/crash-2026-08-20_12.51.04-client.txt]。
        // 束縛してから glGetTexImage (GL 1.0) を呼ぶ形なら、**どちらの GL でも動く**。
        //
        // ⚠ 束縛は**必ず元へ戻す**こと。Blaze3D の GlStateManager は束縛を控えているので、
        // 生の GL で変えたままにすると控えと実際がずれ、MC が別のテクスチャを読む
        // [5c-1b の黒画面と同じ型]。**同じ id へ戻せば控えは正しいまま**である。
        for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) { /* 先客のエラーを捨てる */ }
        int previousBinding = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, ((GlTexture) tex).glId());
        glGetTexImage(GL_TEXTURE_2D, targetMipLevel, GL_RGBA, GL_UNSIGNED_BYTE, texture);
        int err = glGetError();

        // 変えたものを**全て元へ戻す**
        glBindTexture(GL_TEXTURE_2D, previousBinding);
        glPixelStorei(GL_PACK_ROW_LENGTH, prevPackRowLength);
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, prevPackImageHeight);
        glPixelStorei(GL_PACK_SKIP_ROWS, prevPackSkipRows);
        glPixelStorei(GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
        glPixelStorei(GL_PACK_ALIGNMENT, prevPackAlignment);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPackBuf);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);

        if (err != GL_NO_ERROR) {
            throw new IllegalStateException("reading back the block atlas failed with GL error 0x"
                    + Integer.toHexString(err) + " (" + width + "x" + height + ")");
        }

        this.useAtlas(texture, width, height);
    }

    private void useAtlas(int[] texture, int width, int height) {
        // 【規約 11】「落ちなかった」は「読めた」の証拠にならない。
        // 読めていなければ配列は 0 のままで、**焼けるモデルが全て同じ絵になる** —
        // それは規約 1 (取り違えたら絵に出る) を黙って壊す。
        boolean anyOpaque = false;
        for (int px : texture) {
            if ((px & 0xFF000000) != 0) { anyOpaque = true; break; }
        }
        if (!anyOpaque) {
            throw new IllegalStateException("the block atlas read back fully transparent ("
                    + width + "x" + height + ") — every baked model would look identical");
        }

        this.rasterizer.setSamplerTexture(texture, width, height);
    }

    private void bakeBlockModel(BlockState state) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockStateModelSet()
                .get(state);

        List<BlockStateModelPart> out = new ArrayList<>();
        model.collectParts(new SingleThreadedRandomSource(42L), out);
        for (var part : out) {
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                var quads = part.getQuads(direction);
                for (var quad : quads) {
                    (quad.materialInfo().layer()==ChunkSectionLayer.TRANSLUCENT?this.translucentVC:this.opaqueVC)
                            .quad(quad, state.is(BlockTags.LEAVES));
                }
            }
        }
    }


    private void bakeFluidState(BlockState state, int face) {
        this.fr.tesselate(new BlockAndTintGetter() {
            @Override
            public LevelLightEngine getLightEngine() {
                return LevelLightEngine.EMPTY;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public CardinalLighting cardinalLighting() {
                return CardinalLighting.DEFAULT;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                //This is such a stupid and bad hack, we can inject tinting state here since this is called
                // before the quad is added
                //TODO: need to make a quad once tinting thing
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta()|4);//Tinting
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta()|4);//Tinting
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        }, BlockPos.ZERO, layer->{
            if (layer == ChunkSectionLayer.TRANSLUCENT) return this.translucentVC;
            if (layer == ChunkSectionLayer.CUTOUT) {
                this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()|1);//set discard
            } else {
                this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()&~1);//remove discard
            }
            return this.opaqueVC;
        }, state, state.getFluidState());
        this.translucentVC.setDefaultMeta(0);//Reset default meta
        this.opaqueVC.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getUnitVec3i();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE)*8;
    //The outputBuffer layout is different from the non software rasterized ModelTextureBakery
    // in this version the values are simply appended (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        return renderToOutput(state, outputBuffer, false);
    }

    public int renderToOutput(BlockState state, long outputBuffer, boolean rasterAsUV) {
        MemoryUtil.memSet(outputBuffer,0,16*16*8*6);


        boolean isBlock = true;
        if (state.getBlock() instanceof LiquidBlock) {
            isBlock = false;
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            this.bakeBlockModel(state);
            isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty())) {//only render if there... is shit to render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i==1||i==2||i==4);
                    this.rasterizer.clear();
                    this.rasterizer.setUVRaster(rasterAsUV);
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(!rasterAsUV);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
                }
            }
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i);
                if (this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty()) continue;
                isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i==1||i==2||i==4);

                //The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setUVRaster(rasterAsUV);
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(!rasterAsUV);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
            }
        }

        return (isAnyShaded?1:0)|(isAnyDarkend?2:0)|(anyTranslucent?4:0)|(anyDiscard?8:0);
    }




    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.mulPose(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                        2,0,0,0,
                        0,2,0,0,
                        0,0,-2,0,
                        -1,-1,1,1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
