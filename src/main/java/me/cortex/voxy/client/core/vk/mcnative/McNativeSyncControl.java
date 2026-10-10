package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.common.Logger;
import org.lwjgl.vulkan.VK10;

/**
 * <b>The positive control for synchronization validation inside Minecraft</b> (round-27 item 7:
 * the adopted context logs {@code syncValidation=false} because it cannot see what Minecraft's
 * instance enabled). Off unless {@value #FLAG} is set; never set by the product. Once, in Voxy's
 * own submission on Minecraft's device: two {@code vkCmdFillBuffer} writes to the same range with
 * no barrier between them — a write-after-write hazard that synchronization validation must report.
 * The gate requires exactly that report in the launch that sets the flag, so the launches sharing
 * its validation setup are known to run with synchronization validation active.
 */
public final class McNativeSyncControl {
    public static final String FLAG = "voxy.native.syncControl";
    private static boolean done;

    private McNativeSyncControl() {}

    /** At the cutout hook, after the native path drew (Voxy's frame tracker exists by then). */
    static void runOnceIfEnabled() {
        if (done || !Boolean.getBoolean(FLAG) || !VoxyFrames.trackerReady()) return;
        done = true;
        var tracker = VkFrameTracker.get();
        var buffer = new VkBuffer(256);
        try {
            tracker.waitForFrame();
            var cmd = tracker.beginFrame();
            VK10.vkCmdFillBuffer(cmd, buffer.handle, 0, 256, 0x11111111);
            // ⚠ deliberately no barrier: the hazard is the point
            VK10.vkCmdFillBuffer(cmd, buffer.handle, 0, 256, 0x22222222);
            tracker.endFrame();
            tracker.waitForFrame();
            Logger.info("[native-vk] synchronization validation control recorded: two unsynchronised"
                + " fills of one buffer (a write-after-write hazard must be reported)");
        } catch (Throwable t) {
            Logger.error("[native-vk] the synchronization validation control could not run", t);
        } finally {
            buffer.free();
        }
    }

    /** Whether Voxy's frame tracker is initialised (the native path drew at least once). */
    private static final class VoxyFrames {
        static boolean trackerReady() {
            try {
                VkFrameTracker.get();
                return true;
            } catch (IllegalStateException notYet) {
                return false;
            }
        }
    }
}
