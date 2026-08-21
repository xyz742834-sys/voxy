package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.rendering.hierachical.NodeUploadTarget;

/**
 * {@link NodeUploadTarget} の Vulkan 実装 (Phase 5c-4b)。
 *
 * <p>ノードバッファは<b>ホスト可視</b>なので、転送ストリームを挟まず直に書く。
 *
 * <p>⚠ {@link #commitNodes} が何もしないのは<b>意図的</b>である。
 * バリアは<b>フレームの記録側</b>が張る — ここで張ると
 * コマンドバッファの外になり、何も同期しない。
 */
public final class VkNodeUploadTarget implements NodeUploadTarget {
    private final VkBuffer nodes;
    private final int capacity;

    public VkNodeUploadTarget(VkBuffer nodes) {
        this.nodes = nodes;
        this.capacity = (int) (nodes.size() / VkNodeTree.NODE_SIZE);
    }

    @Override
    public long nodeWriteAddress(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= this.capacity) {
            // ⚠ 範囲外は**バリデーションが捕まえない** — 隣のノードを黙って壊すだけ
            throw new IllegalArgumentException("node " + nodeIndex
                + " is outside the node buffer (" + this.capacity + " nodes)");
        }
        return this.nodes.addr() + (long) nodeIndex * VkNodeTree.NODE_SIZE;
    }

    @Override
    public void commitNodes() {
        // 何もしない。ホスト可視なので書いた時点で入っている
    }
}
