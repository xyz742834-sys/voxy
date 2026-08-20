package me.cortex.voxy.client.core.vk.shader;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkTexture;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GL 側 {@code AutoBindingShader} の Vulkan 版。
 *
 * <h2>GL 版からの写像</h2>
 * GL 版は {@code (target, index)} をキーにバインディングを保持し、{@code bind()} のたびに
 * {@code glBindBufferBase} を再発行していた。Vulkan では
 * <b>シェーダごとに VkDescriptorSet を 1 つ永続保持し、内容が変わったときだけ
 * {@code vkUpdateDescriptorSets} で書き戻す</b>方式に写す。
 * これは現行の使われ方 (構築時に 1 回設定して以降不変) に最も近い。
 *
 * <h2>GL 版との意図的な差異</h2>
 * <ol>
 *   <li><b>重複判定キーから {@code target} を外した。</b> GL 版は
 *       {@code entry.target == binding.target && entry.index == binding.index} で判定しており、
 *       同一 index に UBO と SSBO を同時登録できてしまう。GL では名前空間が別なので合法だが、
 *       set 0 に集約する Vulkan では衝突する。index のみをキーとし、
 *       シェーダが宣言している型と食い違う登録は例外にする。</li>
 *   <li><b>{@code rebuild} フラグを復活させた。</b> GL 版では書かれるだけで読まれていない
 *       死んだフィールドだったが、Vulkan では更新回数を抑える意味があるため実際に使う。</li>
 *   <li><b>in-flight 更新を検出する。</b> GPU が参照中の set を書き換えるのは未定義動作のため、
 *       {@link VkFrameTracker} で世代を照合し、違反したら
 *       どのシェーダのどの binding が差し替えられたかを含めて例外を投げる。</li>
 * </ol>
 */
public class VkAutoBindingShader extends VkShader {
    private record BufferBinding(int index, VkBuffer buffer, long offset, long range) {}
    private record ImageBinding(int index, long view, VkSampler sampler, int layout) {}

    private final List<BufferBinding> pending = new ArrayList<>();
    private final List<ImageBinding> pendingImages = new ArrayList<>();
    private long descriptorPool = VK_NULL_HANDLE;
    private long descriptorSet = VK_NULL_HANDLE;

    private boolean rebuild = true;
    /** この set を最後に記録したサブミッション世代。-1 は未使用。 */
    private long lastUsedGeneration = -1;

    VkAutoBindingShader(VkShader.Builder<?> builder, CompiledStages compiled) {
        super(builder, compiled);
    }

    // ---------------- binding registration (GL 版と同じ API 形状) ----------------

    public VkAutoBindingShader ssbo(String define, VkBuffer buffer) {
        return this.ssbo(this.resolve(define), buffer, 0);
    }

    public VkAutoBindingShader ssboIf(String define, VkBuffer buffer) {
        return this.defines().containsKey(define) ? this.ssbo(define, buffer) : this;
    }

    public VkAutoBindingShader ssbo(int index, VkBuffer buffer) {
        return this.ssbo(index, buffer, 0);
    }

    public VkAutoBindingShader ssbo(int index, VkBuffer buffer, long offset) {
        return this.buffer(index, buffer, offset, buffer.size() - offset, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    }

    /**
     * 明示 range 付き。GL の {@code glBindBufferRange(target, index, buf, offset, size)} 相当。
     *
     * <p><b>range を省略してはいけない場面がある</b>: SSBO の可変長配列の要素数は
     * range から決まるため、「バッファ末尾まで」で代用すると配列長が変わる。
     * 例: {@code NodeCleaner.resultTransformer} は同一バッファの
     * {@code [0, 4*OUTPUT_COUNT)} と {@code [4*OUTPUT_COUNT, 12*OUTPUT_COUNT)} を
     * binding 0 と 2 に割り当てている。
     */
    public VkAutoBindingShader ssbo(int index, VkBuffer buffer, long offset, long range) {
        return this.buffer(index, buffer, offset, range, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    }

    public VkAutoBindingShader ubo(String define, VkBuffer buffer) {
        return this.ubo(this.resolve(define), buffer, 0);
    }

    public VkAutoBindingShader ubo(int index, VkBuffer buffer) {
        return this.ubo(index, buffer, 0);
    }

    public VkAutoBindingShader ubo(int index, VkBuffer buffer, long offset) {
        return this.buffer(index, buffer, offset, buffer.size() - offset, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    }

    public VkAutoBindingShader ubo(int index, VkBuffer buffer, long offset, long range) {
        return this.buffer(index, buffer, offset, range, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    }

    /**
     * dynamic offset 付き binding の「窓」を登録する。
     * オフセットは記録時に {@link #setDynamicOffset(int, long)} で与える。
     *
     * <p>{@code offset} は descriptor 側の基準位置 (通常 0)、
     * {@code range} は 1 回のディスパッチで見える窓の大きさ。
     * Vulkan は {@code offset + dynamicOffset + range <= bufferSize} を要求するため、
     * range は「起こりうる最大の窓」ではなく実際に使う大きさにすること。
     */
    public VkAutoBindingShader dynamicSsbo(int index, VkBuffer buffer, long baseOffset, long range) {
        if (!this.isDynamic(index)) {
            throw new IllegalArgumentException("binding " + index + " of shader '" + this.debugName()
                + "' was not declared with Builder.dynamicBinding(" + index + ")");
        }
        return this.buffer(index, buffer, baseOffset, range, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    }

    /**
     * サンプラ付きテクスチャをバインドする ({@code COMBINED_IMAGE_SAMPLER})。
     *
     * <p>GL 側はテクスチャユニットとサンプラを別々にバインドしていたが、
     * Vulkan では 1 つの descriptor にまとまる。
     *
     * <p>レイアウトはここでは遷移させない。<b>いつ遷移するかは呼び出し側が決める</b>
     * ため ({@link me.cortex.voxy.client.core.vk.VkTexture#barrier})、
     * ここでは記録時点のレイアウトが読み取り可能かどうかだけ検査する。
     */
    public VkAutoBindingShader texture(int index, VkTexture texture, VkSampler sampler) {
        return this.image(index, texture.view(), sampler,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
    }

    public VkAutoBindingShader texture(String define, VkTexture texture, VkSampler sampler) {
        return this.texture(this.resolve(define), texture, sampler);
    }

    /** 特定 mip レベルだけを見せる。HiZ のようにレベルを絞って読む場合に使う。 */
    public VkAutoBindingShader textureLevel(int index, VkTexture texture, int level, VkSampler sampler) {
        return this.image(index, texture.view(level), sampler,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
    }

    /** {@code image2D} (STORAGE_IMAGE) としてバインドする。 */
    public VkAutoBindingShader storageImage(int index, VkTexture texture, int level) {
        return this.image(index, texture.view(level), null,
            VK_IMAGE_LAYOUT_GENERAL, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
    }

    private VkAutoBindingShader image(int index, long view, VkSampler sampler,
                                      int layout, int expectedType) {
        this.assertNotFreed();
        var declared = this.bindingAt(index);
        if (declared == null) {
            throw new IllegalArgumentException("shader '" + this.debugName()
                + "' declares no descriptor at set 0 binding " + index);
        }
        if (declared.descriptorType() != expectedType) {
            throw new IllegalArgumentException("binding type mismatch at set 0 binding " + index
                + " in shader '" + this.debugName() + "': shader declares " + declared.typeName()
                + " ('" + declared.name() + "')");
        }
        for (int i = 0; i < this.pendingImages.size(); i++) {
            if (this.pendingImages.get(i).index() == index) {
                var old = this.pendingImages.get(i);
                if (old.view() == view && old.sampler() == sampler && old.layout() == layout) return this;
                this.assertSafeToMutateImage(index);
                this.pendingImages.set(i, new ImageBinding(index, view, sampler, layout));
                this.rebuild = true;
                return this;
            }
        }
        this.assertSafeToMutateImage(index);
        this.pendingImages.add(new ImageBinding(index, view, sampler, layout));
        this.rebuild = true;
        return this;
    }

    private void assertSafeToMutateImage(int index) {
        if (this.lastUsedGeneration < 0) return;
        if (!VkFrameTracker.isInFlight(this.lastUsedGeneration)) return;
        throw new IllegalStateException(
            "in-flight descriptor update: shader '" + this.debugName() + "' set 0 binding " + index
          + " (image) was changed while submission generation " + this.lastUsedGeneration
          + " may still be executing (last completed = " + VkFrameTracker.completedGeneration() + ").");
    }

    private int resolve(String define) {
        String v = this.defines().get(define);
        if (v == null) {
            throw new IllegalArgumentException("shader '" + this.debugName() + "' has no define '" + define
                + "'; available: " + this.defines().keySet());
        }
        return Integer.parseInt(v.trim());
    }

    private VkAutoBindingShader buffer(int index, VkBuffer buffer, long offset, long range, int expectedType) {
        this.assertNotFreed();
        var declared = this.bindingAt(index);
        if (declared == null) {
            throw new IllegalArgumentException("shader '" + this.debugName() + "' declares no descriptor at set 0 binding "
                + index + "; declared bindings: " + this.bindings());
        }
        if (declared.descriptorType() != expectedType) {
            throw new IllegalArgumentException("binding type mismatch at set 0 binding " + index
                + " in shader '" + this.debugName() + "': shader declares " + declared.typeName()
                + " ('" + declared.name() + "') but caller bound a "
                + (expectedType == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER ? "STORAGE_BUFFER" : "UNIFORM_BUFFER"));
        }
        if (range <= 0) {
            throw new IllegalArgumentException("binding " + index + " of '" + this.debugName()
                + "': range must be > 0, got " + range);
        }
        if (offset + range > buffer.size()) {
            throw new IllegalArgumentException("binding " + index + " of '" + this.debugName()
                + "': offset(" + offset + ") + range(" + range + ") exceeds buffer size " + buffer.size());
        }

        // index のみをキーに置換する (GL 版は target も見ていた -- 上の #1 を参照)
        for (int i = 0; i < this.pending.size(); i++) {
            if (this.pending.get(i).index() == index) {
                var old = this.pending.get(i);
                if (old.buffer() == buffer && old.offset() == offset && old.range() == range) {
                    return this; // 実質変化なし。dirty にしない
                }
                this.assertSafeToMutate(index, old.buffer(), buffer);
                this.pending.set(i, new BufferBinding(index, buffer, offset, range));
                this.rebuild = true;
                return this;
            }
        }
        this.assertSafeToMutate(index, null, buffer);
        this.pending.add(new BufferBinding(index, buffer, offset, range));
        this.rebuild = true;
        return this;
    }

    /**
     * GPU が参照中の descriptor set を書き換えようとしていないか検査する。
     *
     * <p>この検査に引っかかる典型は、フレームの途中でバッファを再確保して差し替える経路。
     * GL 版ではそれが安全だったため、そのまま移植すると踏む。
     */
    private void assertSafeToMutate(int index, VkBuffer from, VkBuffer to) {
        if (this.lastUsedGeneration < 0) return; // まだ一度も記録されていない
        if (!VkFrameTracker.isInFlight(this.lastUsedGeneration)) return;
        throw new IllegalStateException(
            "in-flight descriptor update: shader '" + this.debugName() + "' set 0 binding " + index
          + " was changed while submission generation " + this.lastUsedGeneration
          + " may still be executing (last completed = " + VkFrameTracker.completedGeneration() + ").\n"
          + "    binding name : " + (this.bindingAt(index) == null ? "?" : this.bindingAt(index).name()) + "\n"
          + "    old buffer   : " + describe(from) + "\n"
          + "    new buffer   : " + describe(to) + "\n"
          + "  Rewriting a descriptor set that the GPU may be reading is undefined behaviour in Vulkan.\n"
          + "  Either wait for the submission to complete, or give this shader per-frame descriptor sets.");
    }

    private static String describe(VkBuffer b) {
        return b == null ? "(none)" : ("handle=0x" + Long.toHexString(b.handle) + " size=" + b.size());
    }

    // ---------------- descriptor set lifecycle ----------------

    // ---------------- dynamic offset ----------------

    /** binding index -> 次の記録で使う dynamic offset。 */
    private final java.util.TreeMap<Integer, Long> dynamicOffsets = new java.util.TreeMap<>();

    /**
     * dynamic offset を設定する。descriptor set の更新は起きないため
     * <b>フレーム途中でも安全に変更できる</b> (これが dynamic offset を採る主な利点)。
     * 値は次の {@link #bind} で使われる。
     */
    public VkAutoBindingShader setDynamicOffset(int index, long offset) {
        if (!this.isDynamic(index)) {
            throw new IllegalArgumentException("binding " + index + " of shader '" + this.debugName()
                + "' is not a dynamic binding");
        }
        long align = VkContext.get().minStorageBufferOffsetAlignment;
        if (offset % align != 0) {
            throw new IllegalArgumentException("dynamic offset " + offset + " for binding " + index
                + " of '" + this.debugName() + "' is not a multiple of minStorageBufferOffsetAlignment("
                + align + ")");
        }
        this.dynamicOffsets.put(index, offset);
        return this;
    }

    /**
     * descriptor set を更新し、コマンドバッファに bind する。
     * dynamic offset は {@code pDynamicOffsets} として渡すので set の書き換えは起きない。
     */
    public void bind(VkCommandBuffer cmd, int bindPoint) {
        long set = this.updateAndGetSet();
        var indices = this.dynamicBindingIndices();
        try (MemoryStack stack = stackPush()) {
            java.nio.IntBuffer offsets = null;
            if (!indices.isEmpty()) {
                offsets = stack.mallocInt(indices.size());
                for (int i = 0; i < indices.size(); i++) {
                    Long v = this.dynamicOffsets.get(indices.get(i));
                    if (v == null) {
                        throw new IllegalStateException("shader '" + this.debugName() + "': binding "
                            + indices.get(i) + " is dynamic but no offset was set before bind()");
                    }
                    offsets.put(i, Math.toIntExact(v));
                }
            }
            vkCmdBindDescriptorSets(cmd, bindPoint, this.pipelineLayout(), 0,
                stack.longs(set), offsets);
        }
        this.markUsed();
    }

    // ---------------- push constants ----------------

    /**
     * push constant のホスト側シャドウバッファ。setter はここに書くだけで、
     * {@link #flushPushConstants} がコマンドバッファへ流す。
     *
     * <p><b>flush は任意の回数呼べる。</b> 「ディスパッチ直前に一度だけ」では
     * traversal のように反復ごとに値が変わるループが表現できないため
     * (docs/phase3-descriptor-survey.md 6)。
     */
    private java.nio.ByteBuffer pushShadow;

    private java.nio.ByteBuffer pushShadow() {
        if (this.pushConstantSize() == 0) {
            throw new IllegalStateException("shader '" + this.debugName() + "' has no push constant block");
        }
        if (this.pushShadow == null) {
            this.pushShadow = org.lwjgl.system.MemoryUtil.memCalloc(this.pushConstantSize());
        }
        return this.pushShadow;
    }

    public VkAutoBindingShader pushUInt(int offset, int value) {
        this.pushShadow().putInt(offset, value);
        return this;
    }

    public VkAutoBindingShader pushFloat(int offset, float value) {
        this.pushShadow().putFloat(offset, value);
        return this;
    }

    /** 任意のバイト列を書く (mat4 などのまとまった値用)。 */
    public VkAutoBindingShader pushBytes(int offset, java.nio.ByteBuffer src) {
        var dst = this.pushShadow().duplicate();
        dst.position(offset).put(src.duplicate());
        return this;
    }

    /**
     * シャドウバッファの現在値をコマンドバッファへ記録する。
     * 各ディスパッチ/ドローの記録直前に呼ぶ。何度呼んでもよい。
     */
    public void flushPushConstants(VkCommandBuffer cmd) {
        if (this.pushConstantSize() == 0) return;
        vkCmdPushConstants(cmd, this.pipelineLayout(), this.pushConstantStages(), 0, this.pushShadow());
    }

    /**
     * 保留中のバインディングを descriptor set に反映する。
     * コマンドバッファに記録する直前に呼ぶこと。変更が無ければ何もしない。
     */
    public long updateAndGetSet() {
        this.assertNotFreed();
        if (this.descriptorSet == VK_NULL_HANDLE) this.allocateSet();
        if (this.rebuild) {
            this.writeDescriptors();
            this.rebuild = false;
        }
        return this.descriptorSet;
    }

    /**
     * この set をコマンドバッファに記録したことを表明する。
     * 以降、対応するサブミッションが完了するまで更新は拒否される。
     */
    public void markUsed() {
        this.lastUsedGeneration = VkFrameTracker.currentGeneration();
    }

    private void allocateSet() {
        var ctx = VkContext.get();
        try (MemoryStack stack = stackPush()) {
            // このシェーダが宣言している型ごとに必要数を数える
            // プールは layout と同じ型で数える (dynamic 宣言を反映する)
            var counts = new java.util.LinkedHashMap<Integer, Integer>();
            for (var b : this.bindings()) {
                counts.merge(this.descriptorTypeFor(b.binding()), b.count(), Integer::sum);
            }
            if (counts.isEmpty()) {
                throw new IllegalStateException("shader '" + this.debugName() + "' declares no descriptors");
            }
            var sizes = VkDescriptorPoolSize.calloc(counts.size(), stack);
            int k = 0;
            for (var e : counts.entrySet()) {
                sizes.get(k++).type(e.getKey()).descriptorCount(e.getValue());
            }

            var pci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(1).pPoolSizes(sizes);
            long[] p = new long[1];
            VkContext.check(vkCreateDescriptorPool(ctx.device, pci, null, p), "vkCreateDescriptorPool");
            this.descriptorPool = p[0];

            var ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(this.descriptorPool)
                .pSetLayouts(stack.longs(this.descriptorSetLayout()));
            VkContext.check(vkAllocateDescriptorSets(ctx.device, ai, p), "vkAllocateDescriptorSets");
            this.descriptorSet = p[0];
        }
    }

    private void writeDescriptors() {
        if (this.pending.isEmpty() && this.pendingImages.isEmpty()) return;
        var ctx = VkContext.get();
        try (MemoryStack stack = stackPush()) {
            int n = this.pending.size() + this.pendingImages.size();
            var writes = VkWriteDescriptorSet.calloc(n, stack);
            for (int i = 0; i < this.pending.size(); i++) {
                var b = this.pending.get(i);
                b.buffer().assertNotFreed();
                var info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(b.buffer().handle).offset(b.offset()).range(b.range());
                writes.get(i).sType$Default()
                    .dstSet(this.descriptorSet)
                    .dstBinding(b.index())
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    // layout 側で *_DYNAMIC にしている binding は write も同じ型でなければならない
                    .descriptorType(this.descriptorTypeFor(b.index()))
                    .pBufferInfo(info);
            }
            for (int k = 0; k < this.pendingImages.size(); k++) {
                var im = this.pendingImages.get(k);
                var info = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(im.view())
                    .imageLayout(im.layout())
                    .sampler(im.sampler() == null ? VK_NULL_HANDLE : im.sampler().handle);
                writes.get(this.pending.size() + k).sType$Default()
                    .dstSet(this.descriptorSet)
                    .dstBinding(im.index())
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(this.bindingAt(im.index()).descriptorType())
                    .pImageInfo(info);
            }
            vkUpdateDescriptorSets(ctx.device, writes, null);
        }
    }

    /** layout 生成時と同じ規則で descriptor type を決める (dynamic 宣言を反映)。 */
    private int descriptorTypeFor(int index) {
        int base = this.bindingAt(index).descriptorType();
        if (!this.isDynamic(index)) return base;
        return base == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
            ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC
            : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    }

    /** シェーダが宣言している binding のうち、まだバインドされていないものを返す。 */
    public List<Integer> unboundBindings() {
        List<Integer> missing = new ArrayList<>();
        for (var b : this.bindings()) {
            boolean found = false;
            for (var p : this.pending) if (p.index() == b.binding()) { found = true; break; }
            if (!found) for (var p : this.pendingImages) if (p.index() == b.binding()) { found = true; break; }
            if (!found) missing.add(b.binding());
        }
        return missing;
    }

    @Override
    public void free() {
        if (this.pushShadow != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.pushShadow);
            this.pushShadow = null;
        }
        if (this.descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(VkContext.get().device, this.descriptorPool, null);
            this.descriptorPool = VK_NULL_HANDLE;
            this.descriptorSet = VK_NULL_HANDLE;
        }
        super.free();
    }
}
