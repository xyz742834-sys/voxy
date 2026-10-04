package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.LinkedHashSet;

/** Coalesce block packets into section snapshots for the diagnostic Vulkan path. */
public final class VulkanWorldUpdates {
    private record Section(int x, int y, int z) {}
    private static final LinkedHashSet<Section> PENDING = new LinkedHashSet<>();
    private static ClientLevel pendingLevel;

    private VulkanWorldUpdates() {}

    /** Called on the client thread by ClientLevel.setBlocksDirty. */
    public static void markDirty(ClientLevel level, BlockPos pos) {
        if (pendingLevel != level) {
            PENDING.clear();
            pendingLevel = level;
        }
        PENDING.add(new Section(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
    }

    /** Bounded client-thread snapshot work; voxel conversion stays in the ingest service. */
    public static void tick(Minecraft mc) {
        if (mc.level != pendingLevel || VoxyClient.backend() != VoxyClient.Backend.VULKAN
                || !VoxyConfig.CONFIG.ingestEnabled) {
            PENDING.clear();
            pendingLevel = null;
            return;
        }
        if (PENDING.isEmpty()) return;
        var world = WorldIdentifier.ofEngineNullable(mc.level);
        if (world == null) return;
        var iterator = PENDING.iterator();
        for (int count = 0; count < 64 && iterator.hasNext(); count++) {
            var key = iterator.next();
            iterator.remove();
            var chunk = mc.level.getChunkSource().getChunk(key.x, key.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            int sectionIndex = key.y - (mc.level.getMinY() >> 4);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) continue;
            var pos = SectionPos.of(key.x, key.y, key.z);
            var light = mc.level.getLightEngine();
            var blockLight = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
            var skyLight = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
            VoxelIngestService.rawIngest(world, chunk.getSection(sectionIndex), key.x, key.y, key.z,
                blockLight == null ? null : blockLight.copy(), skyLight == null ? null : skyLight.copy());
        }
        if (PENDING.isEmpty()) pendingLevel = null;
    }
}
