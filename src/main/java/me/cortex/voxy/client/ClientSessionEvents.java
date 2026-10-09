package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

public class ClientSessionEvents {
    public static boolean inSession = false;

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
            }
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;

        if (VoxyClient.backend() == VoxyClient.Backend.VULKAN) {
            me.cortex.voxy.client.core.vk.interop.VkInteropProbe.shutdown();
        }
        // native experiments: drop the block-atlas copy with the session (round-21 R21-ATLAS-RESET)
        me.cortex.voxy.client.core.vk.mcnative.McNativeAtlas.reset();
        VoxyCommon.shutdownInstance();
    }
}
