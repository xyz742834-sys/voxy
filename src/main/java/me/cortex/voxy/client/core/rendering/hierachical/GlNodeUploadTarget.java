package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;

/**
 * {@link NodeUploadTarget} の GL 実装 (Phase 5c-4b)。
 *
 * <p>⚠ 中身は {@code NodeManager.writeChanges} から<b>そのまま移した</b>ものである。
 * 参照仕様を変えないため、式には手を入れていない。
 */
public final class GlNodeUploadTarget implements NodeUploadTarget {
    private final GlBuffer nodeBuffer;

    public GlNodeUploadTarget(GlBuffer nodeBuffer) {
        this.nodeBuffer = nodeBuffer;
    }

    @Override
    public long nodeWriteAddress(int nodeIndex) {
        return UploadStream.INSTANCE.upload(this.nodeBuffer, nodeIndex * 16L, 16L);
    }

    @Override
    public void commitNodes() {
        UploadStream.INSTANCE.commit();
    }
}
