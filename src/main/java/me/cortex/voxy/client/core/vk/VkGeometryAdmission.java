package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;

/** Bounded, retryable admission to the geometry arena. No graphics-context dependency. */
public final class VkGeometryAdmission {
    private final BasicAsyncGeometryManager geometry;
    private final VkHierarchicalScene.GeometryReclaimer reclaimer;
    private final NodeManager nodes;
    private final int evictionLimit;
    private boolean exhausted;
    private long rejected;
    private int reclaimAttempts;

    public VkGeometryAdmission(BasicAsyncGeometryManager geometry,
                               VkHierarchicalScene.GeometryReclaimer reclaimer,
                               NodeManager nodes, int evictionLimit) {
        if (evictionLimit < 1) throw new IllegalArgumentException("evictionLimit must be positive");
        this.geometry = geometry;
        this.reclaimer = reclaimer;
        this.nodes = nodes;
        this.evictionLimit = evictionLimit;
    }

    /** Consumes built on both outcomes. A rejection must remain eligible for remeshing. */
    public boolean accept(BuiltSection built) {
        long need = VkHierarchicalScene.geometryBytesNeeded(built);
        this.reclaimAttempts = 0;
        if (!this.geometry.canFit(need)) {
            this.reclaimAttempts = this.reclaimer.reclaimWhile(() -> !this.geometry.canFit(need), this.evictionLimit);
        }
        if (!this.geometry.canFit(need)) {
            this.exhausted = true;
            this.rejected++;
            built.free();
            return false;
        }
        this.nodes.processGeometryResult(built);
        this.exhausted = false;
        return true;
    }

    public boolean exhausted() { return this.exhausted; }
    public long rejected() { return this.rejected; }
    public int reclaimAttempts() { return this.reclaimAttempts; }
}
