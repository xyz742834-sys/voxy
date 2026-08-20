package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static me.cortex.voxy.client.core.vk.VkContext.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GlBuffer + GlPersistentMappedBuffer の統合版。
 *
 * Apple Silicon はユニファイドメモリで DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT が
 * 同一メモリタイプになるため、「永続マップされた buffer」と「されていない buffer」を
 * 区別する意味がない。全てのバッファが常時マップされ addr() が有効。
 * ステージングバッファもコピーコマンドも不要。
 */
public class VkBuffer extends TrackedObject {
    public final long handle;
    public final long memory;
    private final long size;
    private final long addr;

    private static int COUNT;
    private static long TOTAL_SIZE;

    /** 汎用途 (SSBO + indirect + index + transfer)。 */
    public static final int DEFAULT_USAGE =
          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
        | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
        | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
        | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    public VkBuffer(long size) {
        this(size, DEFAULT_USAGE, true);
    }

    public VkBuffer(long size, boolean zero) {
        this(size, DEFAULT_USAGE, zero);
    }

    public VkBuffer(MemoryBuffer src) {
        this(src.size, DEFAULT_USAGE, false);
        MemoryUtil.memCopy(src.address, this.addr, src.size);
    }

    public VkBuffer(long size, int usage, boolean zero) {
        VkContext ctx = VkContext.get();
        this.size = size;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack)
                .sType$Default().size(size).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            long[] p = new long[1];
            check(vkCreateBuffer(ctx.device, bci, null, p), "vkCreateBuffer");
            this.handle = p[0];

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(ctx.device, handle, req);

            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default().allocationSize(req.size())
                .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                  | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                  | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(vkAllocateMemory(ctx.device, mai, null, p), "vkAllocateMemory");
            this.memory = p[0];
            check(vkBindBufferMemory(ctx.device, handle, memory, 0), "vkBindBufferMemory");

            PointerBuffer pData = stack.mallocPointer(1);
            check(vkMapMemory(ctx.device, memory, 0, size, 0, pData), "vkMapMemory");
            this.addr = pData.get(0);
        }

        if (zero) this.zero();

        COUNT++;
        TOTAL_SIZE += size;
    }

    @Override
    public void free() {
        this.free0();
        VkContext ctx = VkContext.get();
        vkUnmapMemory(ctx.device, memory);
        vkDestroyBuffer(ctx.device, handle, null);
        vkFreeMemory(ctx.device, memory, null);
        COUNT--;
        TOTAL_SIZE -= this.size;
    }

    public long size() { return this.size; }

    /** 永続マップ済みのホストアドレス。常に有効。 */
    public long addr() { return this.addr; }

    /**
     * sparse buffer は使用しない。
     * MoltenVK は sparseBinding=false であり、Voxy の sparse 経路は
     * 元々 NVIDIA+Windows 限定のフォールバックだった。
     */
    public boolean isSparse() { return false; }

    /** ホスト側から直接ゼロ埋め。ユニファイドメモリなのでコマンド不要。 */
    public VkBuffer zero() {
        MemoryUtil.memSet(this.addr, 0, this.size);
        return this;
    }

    public VkBuffer zeroRange(long offset, long size) {
        MemoryUtil.memSet(this.addr + offset, 0, size);
        return this;
    }

    /** 4 バイト値で埋める。 */
    public VkBuffer fill(int data) {
        long words = this.size >> 2;
        for (long i = 0; i < words; i++) {
            MemoryUtil.memPutInt(this.addr + (i << 2), data);
        }
        return this;
    }

    public static int getCount() { return COUNT; }
    public static long getTotalSize() { return TOTAL_SIZE; }

    public VkBuffer name(String name) {
        //TODO: VK_EXT_debug_utils による命名
        return this;
    }
}
