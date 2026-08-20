package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
import me.cortex.voxy.common.util.GlobalCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL12C.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglTextureSubImage2D;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore implements ModelUploadTarget {
    public static final int MODEL_SIZE = 64;
    private Cleaner.Cleanable ref;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        var tex = this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
        this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }


    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.ref.clean();
        glDeleteSamplers(this.blockSampler);
    }


    // ---------------- ModelUploadTarget (GL 実装) ----------------
    //
    // ⚠ 中身は ModelFactory.ModelBakeResultUpload から**そのまま移した**ものである。
    // 参照仕様を変えないため、式には手を入れていない —
    // アトラス内の配置だけ ModelAtlasLayout に寄せた (GL と Vulkan で 1 つの式にするため)。

    @Override
    public void beginUploads() {
        // ⚠ ModelFactory.processUploads から**そのまま移した**もの。
        // アトラスへ nglTextureSubImage2D で流す前に、アンパック指定を既定へ戻す必要がある
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    }

    @Override
    public void commitUploads() {
        // ⚠ これを呼ばないと UploadStream に積んだ分が**GPU に届かない**
        UploadStream.INSTANCE.commit();
    }

    @Override
    public void uploadModel(int modelId, MemoryBuffer model) {
        model.cpyTo(UploadStream.INSTANCE.upload(this.modelBuffer,
            (long) modelId * MODEL_SIZE, MODEL_SIZE));
    }

    @Override
    public void uploadBiomeColours(int index, MemoryBuffer colours) {
        colours.cpyTo(UploadStream.INSTANCE.upload(this.modelColourBuffer,
            index * 4L, colours.size));
    }

    @Override
    public void uploadBiomeColourTable(MemoryBuffer colours) {
        colours.cpyTo(UploadStream.INSTANCE.upload(this.modelColourBuffer, 0, colours.size));
    }

    @Override
    public void patchModelBiomeIndex(int modelId, int biomeIndex) {
        MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(this.modelBuffer,
            (long) MODEL_SIZE * modelId + 4 * 6 + 4, 4), biomeIndex);
    }

    @Override
    public void uploadModelTexture(int modelId, MemoryBuffer texture, int mipLevels) {
        int face = ModelAtlasLayout.FACE_TEXELS;
        int x = ModelAtlasLayout.tileX(modelId, face);
        int y = ModelAtlasLayout.tileY(modelId, face);
        for (int lvl = 0; lvl < mipLevels; lvl++) {
            nglTextureSubImage2D(this.textures.id, lvl, x >> lvl, y >> lvl,
                ModelAtlasLayout.tileWidth(face, lvl), ModelAtlasLayout.tileHeight(face, lvl),
                GL_RGBA, GL_UNSIGNED_BYTE,
                texture.address + ModelAtlasLayout.mipByteOffset(face, lvl));
        }
    }

    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
