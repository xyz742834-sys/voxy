package me.cortex.voxy.client.core.vk.shader;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkTexture;
import me.cortex.voxy.client.core.vk.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 同一レイアウトの descriptor set を <b>N 個ぶん事前生成</b>して保持する。
 *
 * <h2>用途</h2>
 * ディスパッチのたびにバインドが切り替わるが、<b>組み合わせが有限</b>なパス向け
 * (docs/phase3-descriptor-survey.md カテゴリ B)。
 *
 * <p>代表例が traversal のフリップフロップ。{@code MAX_ITERATIONS = 5} 回まわるが
 * (SOURCE, SINK) の組は 3 通りしかない [確認済]:
 * <pre>
 *   variant 0 : SOURCE=topNodeIds,   SINK=scratchB   (iter 0)
 *   variant 1 : SOURCE=scratchB,     SINK=scratchA   (iter 1, 3)
 *   variant 2 : SOURCE=scratchA,     SINK=scratchB   (iter 2, 4)
 * </pre>
 *
 * <h2>なぜ set を作り分けるのか</h2>
 * 1 つの set を書き換えて使い回すことはできない。descriptor set は
 * <b>記録時ではなく実行時に読まれる</b>ため、同じ set を反復ごとに更新すると
 * 先に記録したディスパッチも最後の内容を見てしまう。
 * 事前生成して {@code vkCmdBindDescriptorSets} で切り替えれば更新が一切起きない。
 */
public class VkDescriptorSetGroup {
    private record Write(int variant, int binding, VkBuffer buffer, long offset, long range) {}
    private record ImageWrite(int variant, int binding, long view, VkSampler sampler, int layout) {}

    private final VkShader shader;
    private final int variantCount;
    private final long pool;
    private final long[] sets;
    private final Map<Integer, Write> pending = new LinkedHashMap<>();
    private final Map<Integer, ImageWrite> pendingImages = new LinkedHashMap<>();
    private boolean dirty = true;
    private boolean freed;

    public VkDescriptorSetGroup(VkShader shader, int variantCount) {
        if (variantCount < 1) throw new IllegalArgumentException("variantCount must be >= 1");
        this.shader = shader;
        this.variantCount = variantCount;

        var ctx = VkContext.get();
        try (MemoryStack stack = stackPush()) {
            var counts = new LinkedHashMap<Integer, Integer>();
            for (var b : shader.bindings()) {
                int type = shader.isDynamic(b.binding())
                    ? (b.descriptorType() == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                        ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC
                        : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC)
                    : b.descriptorType();
                counts.merge(type, b.count() * variantCount, Integer::sum);
            }
            if (counts.isEmpty()) {
                throw new IllegalStateException("shader '" + shader.debugName() + "' declares no descriptors");
            }
            var sizes = VkDescriptorPoolSize.calloc(counts.size(), stack);
            int k = 0;
            for (var e : counts.entrySet()) sizes.get(k++).type(e.getKey()).descriptorCount(e.getValue());

            var pci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(variantCount).pPoolSizes(sizes);
            long[] p = new long[1];
            VkContext.check(vkCreateDescriptorPool(ctx.device, pci, null, p), "vkCreateDescriptorPool");
            this.pool = p[0];

            var layouts = stack.mallocLong(variantCount);
            for (int i = 0; i < variantCount; i++) layouts.put(i, shader.descriptorSetLayout());
            var ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(this.pool).pSetLayouts(layouts);
            this.sets = new long[variantCount];
            VkContext.check(vkAllocateDescriptorSets(ctx.device, ai, this.sets), "vkAllocateDescriptorSets");
        }
    }

    // ---------------- binding ----------------

    /** 全 variant に同じバッファを割り当てる (静的なバインディング用)。 */
    public VkDescriptorSetGroup shared(int binding, VkBuffer buffer) {
        for (int v = 0; v < this.variantCount; v++) this.variant(v, binding, buffer);
        return this;
    }

    public VkDescriptorSetGroup shared(String define, VkBuffer buffer) {
        return this.shared(this.resolve(define), buffer);
    }

    /** 特定の variant にだけ割り当てる (フリップフロップする binding 用)。 */
    public VkDescriptorSetGroup variant(int variant, int binding, VkBuffer buffer) {
        return this.variant(variant, binding, buffer, 0, buffer.size());
    }

    public VkDescriptorSetGroup variant(int variant, String define, VkBuffer buffer) {
        return this.variant(variant, this.resolve(define), buffer);
    }

    public VkDescriptorSetGroup variant(int variant, int binding, VkBuffer buffer, long offset, long range) {
        this.assertNotFreed();
        if (variant < 0 || variant >= this.variantCount) {
            throw new IndexOutOfBoundsException("variant " + variant + " of " + this.variantCount);
        }
        var declared = this.shader.bindingAt(binding);
        if (declared == null) {
            throw new IllegalArgumentException("shader '" + this.shader.debugName()
                + "' declares no descriptor at set 0 binding " + binding);
        }
        if (offset < 0 || range <= 0 || offset + range > buffer.size()) {
            throw new IllegalArgumentException("binding " + binding + ": offset(" + offset
                + ") + range(" + range + ") exceeds buffer size " + buffer.size());
        }
        this.pending.put(variant * 1000 + binding, new Write(variant, binding, buffer, offset, range));
        this.dirty = true;
        return this;
    }

    /** 全 variant に同じテクスチャを割り当てる。 */
    public VkDescriptorSetGroup sharedTexture(int binding, VkTexture tex, VkSampler sampler) {
        for (int v = 0; v < this.variantCount; v++) this.variantTexture(v, binding, tex, sampler);
        return this;
    }

    public VkDescriptorSetGroup sharedTexture(String define, VkTexture tex, VkSampler sampler) {
        return this.sharedTexture(this.resolve(define), tex, sampler);
    }

    /**
     * <b>1 つのミップレベルだけ</b>を読ませる (Phase 5c-4a)。
     *
     * <p>HiZ の連鎖は「レベル i-1 を読んでレベル i を書く」ので、
     * 読み側を<b>そのレベルに絞る</b>必要がある。
     * GL 版が {@code GL_TEXTURE_BASE_LEVEL/MAX_LEVEL} でやっていることに当たる。
     *
     * <p>⚠ {@code textureGather} には LOD 引数が無いので、
     * <b>ビューを絞る以外に方法がない</b>。
     */
    public VkDescriptorSetGroup variantTextureLevel(int variant, int binding, VkTexture tex,
                                                    int level, VkSampler sampler) {
        this.assertNotFreed();
        if (this.shader.bindingAt(binding) == null) {
            throw new IllegalArgumentException("shader '" + this.shader.debugName()
                + "' declares no descriptor at set 0 binding " + binding);
        }
        this.pendingImages.put(variant * 1000 + binding,
            new ImageWrite(variant, binding, tex.view(level), sampler,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        this.dirty = true;
        return this;
    }

    public VkDescriptorSetGroup variantTexture(int variant, int binding, VkTexture tex, VkSampler sampler) {
        this.assertNotFreed();
        var declared = this.shader.bindingAt(binding);
        if (declared == null) {
            throw new IllegalArgumentException("shader '" + this.shader.debugName()
                + "' declares no descriptor at set 0 binding " + binding);
        }
        this.pendingImages.put(variant * 1000 + binding,
            new ImageWrite(variant, binding, tex.view(), sampler,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        this.dirty = true;
        return this;
    }

    private int resolve(String define) {
        String v = this.shader.defines().get(define);
        if (v == null) {
            throw new IllegalArgumentException("shader '" + this.shader.debugName()
                + "' has no define '" + define + "'");
        }
        return Integer.parseInt(v.trim());
    }

    // ---------------- lifecycle ----------------

    /**
     * 保留中の割り当てを書き戻す。
     * <b>フレームの記録を始める前に済ませること</b> — 記録後に更新すると
     * 既に記録したディスパッチの読み先が変わる。
     */
    public VkDescriptorSetGroup update() {
        this.assertNotFreed();
        if (!this.dirty || (this.pending.isEmpty() && this.pendingImages.isEmpty())) return this;
        var ctx = VkContext.get();
        try (MemoryStack stack = stackPush()) {
            List<Write> ws = new ArrayList<>(this.pending.values());
            List<ImageWrite> is = new ArrayList<>(this.pendingImages.values());
            var writes = VkWriteDescriptorSet.calloc(ws.size() + is.size(), stack);
            for (int i = 0; i < ws.size(); i++) {
                var w = ws.get(i);
                w.buffer().assertNotFreed();
                var info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(w.buffer().handle).offset(w.offset()).range(w.range());
                int type = this.shader.isDynamic(w.binding())
                    ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC
                    : this.shader.bindingAt(w.binding()).descriptorType();
                writes.get(i).sType$Default()
                    .dstSet(this.sets[w.variant()])
                    .dstBinding(w.binding())
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(type)
                    .pBufferInfo(info);
            }
            for (int i = 0; i < is.size(); i++) {
                var im = is.get(i);
                var info = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(im.view())
                    .imageLayout(im.layout())
                    .sampler(im.sampler() == null ? VK_NULL_HANDLE : im.sampler().handle);
                writes.get(ws.size() + i).sType$Default()
                    .dstSet(this.sets[im.variant()])
                    .dstBinding(im.binding())
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(this.shader.bindingAt(im.binding()).descriptorType())
                    .pImageInfo(info);
            }
            vkUpdateDescriptorSets(ctx.device, writes, null);
        }
        this.dirty = false;
        return this;
    }

    /** 指定 variant をコマンドバッファにバインドする。 */
    public void bind(VkCommandBuffer cmd, int bindPoint, int variant) {
        this.assertNotFreed();
        if (this.dirty) {
            throw new IllegalStateException("VkDescriptorSetGroup for '" + this.shader.debugName()
                + "' has pending writes; call update() before recording");
        }
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd, bindPoint, this.shader.pipelineLayout(), 0,
                stack.longs(this.sets[variant]), null);
        }
    }

    /** 宣言されている binding のうち、この variant でまだ割り当てられていないもの。 */
    public List<Integer> unbound(int variant) {
        List<Integer> missing = new ArrayList<>();
        for (var b : this.shader.bindings()) {
            int k = variant * 1000 + b.binding();
            if (!this.pending.containsKey(k) && !this.pendingImages.containsKey(k)) missing.add(b.binding());
        }
        return missing;
    }

    public int variantCount() { return this.variantCount; }
    public long set(int variant) { return this.sets[variant]; }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        vkDestroyDescriptorPool(VkContext.get().device, this.pool, null);
    }

    private void assertNotFreed() {
        if (this.freed) throw new IllegalStateException("VkDescriptorSetGroup already freed");
    }
}
