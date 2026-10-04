# Native Minecraft-Vulkan integration — API survey against the actual candidate

Investigation for the next priority in [project-goal.md](project-goal.md), done
2026-10-04 against the **actual** candidate this project builds on:
`minecraft_version=26.2`, `fabric_api 0.152.2+26.2`,
`sodium mc26.2-0.9.2-alpha.3-fabric`. Every signature below was read from the
class files in those artifacts (`javap` on
`~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` and the Sodium jar), not
from the historical [device-sharing survey](../phase6-device-sharing-survey.md),
whose assumptions this replaces where they differ.

Nothing here is implemented. This is the API basis for deciding the first step.

## Minecraft 26.2 has a real Vulkan backend

`com.mojang.blaze3d.vulkan` contains 73 classes, including `VulkanBackend`,
`VulkanInstance`, `VulkanPhysicalDevice`, `VulkanDevice`, `VulkanQueue`,
`VulkanCommandEncoder`, `VulkanRenderPass`, `VulkanRenderPipeline`,
`VulkanGpuTexture`, `VulkanGpuTextureView`, `VulkanGpuBuffer`,
`VulkanTransientMemory`, `DestructionQueue` and a `checkpoints/` extension for
AMD/NVIDIA breadcrumbs.

`VulkanBackend.REQUIRED_DEVICE_FEATURES` / `REQUIRED_DEVICE_EXTENSIONS` require
**synchronization2** and **dynamic rendering** (plus VK11/VK12 feature structs,
vertex attribute divisor and multi-draw). The disassembly confirms the backend
actually uses them: the encoder issues `vkCmdPipelineBarrier2KHR`,
`vkCmdBeginRenderingKHR` / `vkCmdEndRenderingKHR`, `vkWaitSemaphores` with
timeline-semaphore signal/wait triples, and the render pass issues
`vkCmdPushDescriptorSetKHR`, `vkCmdDraw*`, `vkCmdDrawIndirect`,
`vkCmdDrawIndexedIndirect` and `vkCmdDrawMulti*EXT`.

That is the same feature set Voxy's own Vulkan renderer is built on, which is why
the shader, traversal, culling and table-generation code is a candidate for reuse
rather than a rewrite.

## The three connection points, with real signatures

**1. The device.** `RenderSystem.getDevice()` returns the `GpuDevice` *wrapper*,
whose `GpuDeviceBackend backend` field is private with no accessor; the same is
true of `CommandEncoder.backend()` (protected). So reaching the backend needs a
mixin `@Accessor`/`@Invoker` — standard for this project. The backend itself is
public API once reached:

    class VulkanDevice implements GpuDeviceBackend {
        public VkDevice vkDevice();
        public VulkanInstance instance();
        public VulkanQueue graphicsQueue();   // also computeQueue(), transferQueue()
        public long vma();                    // MC's VMA allocator
        public VulkanCommandEncoder createCommandEncoder();
        public DeviceInfo getDeviceInfo();
    }

This means Voxy would **adopt** MC's instance, physical device, logical device,
queues and allocator instead of creating its own (`VkContext` currently creates
all of them). It also means MoltenVK is already initialized by Minecraft; there is
a `VulkanDevice.isIntegratedIntelMoltenVK` flag, so the backend is MoltenVK-aware.

**2. The colour/depth targets, as raw Vulkan handles.**

    class RenderTarget {
        public GpuTextureView getColorTextureView();   // and getDepthTextureView()
    }
    class VulkanGpuTextureView extends GpuTextureView { public long vkImageView(); public VulkanGpuTexture texture(); }
    class VulkanGpuTexture   extends GpuTexture     { public long vkImage(); }

No GL texture ID, no `GlTextureView` cast, no IOSurface. This removes the reason
`VoxyClient` currently disables itself when Minecraft is not on OpenGL.

**3. The render point — Minecraft's live command buffer.** Sodium 0.9.2 for 26.2
already supports this backend and exposes exactly the handle needed:

    interface VulkanRenderPassAccessor {           // net.caffeinemc.mods.sodium.mixin.core
        VulkanRenderPipeline sodium$getPipeline();
        VkCommandBuffer      sodium$getCommandBuffer();
    }
    interface RenderPassAccessor { RenderPassBackend getBackend(); }

So terrain draws can be recorded into Minecraft's own in-flight command buffer,
inside the dynamic-rendering pass that already has MC's colour/depth bound and
viewport/scissor set. Voxy can mixin an equivalent accessor on
`com.mojang.blaze3d.vulkan.VulkanRenderPass` rather than depending on Sodium's
internal mixin interface.

For work that cannot go inside a render pass — Voxy's compute traversal, culling
and table generation — the encoder hands out a real command buffer and takes it
back:

    class VulkanCommandEncoder implements CommandEncoderBackend {
        public static final int MAX_SUBMITS_IN_FLIGHT;
        public VkCommandBuffer allocateAndBeginTransientCommandBuffer();
        public void execute(VkCommandBuffer);
        public void waitSemaphore(long sem, long value, long stage);
        public void signalSemaphore(long sem, long value, long stage);
        public void submit();
        public void queueForDestroy(Destroyable);
        public GpuFence createFence();
        public static void memoryBarrier(VkCommandBuffer, MemoryStack);
    }
    record VulkanQueue(VkQueue vkQueue, int queueFamilyIndex) {
        Submission beginSubmit();   // Submission.executeCommands(VkCommandBuffer), wait/signalSemaphore
    }

## What this changes about Voxy's current assumptions

- **Resource lifetime.** `queueForDestroy(Destroyable)` plus
  `MAX_SUBMITS_IN_FLIGHT` is the lifetime contract the backend actually offers.
  Voxy's explicit fence waits / `vkDeviceWaitIdle` are its own invention and are
  not supplied by Minecraft; uploads, descriptors and destruction must be adapted
  to the submission lifetime instead.
- **Submission ordering.** Recording into MC's command buffer needs no submit of
  our own; anything submitted separately must order against MC's work through the
  timeline semaphores the encoder/queue already expose, not through device-wide
  waits.
- **Instance/device ownership.** Voxy must stop creating a device when running
  native, which also means its validation-layer setup, queue selection and memory
  allocation paths become MC's.

## Measured on this host (2026-10-04)

`McNativeVulkanProbe` reports from inside the client what Minecraft actually ran, and
`scripts/verify.py --only native` drives it through the eleven-checkpoint lifecycle.
Two results so far:

1. **A preference is not a backend.** With `options.txt` containing
   `preferredGraphicsBackend:"vulkan"`, Minecraft ran
   `com.mojang.blaze3d.opengl.GlDevice` ("Using graphics backend OpenGL, using drivers:
   4.1 Metal - 91.7") and rewrote the file back to `"default"`. This is exactly the
   failure mode project-goal.md warns about, and the probe caught it rather than letting
   the setting stand in for proof.
2. **Minecraft's launch argument is what decides.** `net.minecraft.client.main.Main`
   accepts `--graphicsBackend <default|opengl|vulkan>` and `--vulkanValidation`, and it
   says so at runtime: "Graphics backend forced to vulkan by launch argument, in-game
   preferred graphics backend setting is ignored". With that argument Minecraft ran
   **"graphics backend Vulkan, using drivers: 1.2.357 MoltenVK 1.4.2"** and enabled its
   own validation layers, and the probe reported:

   - backend `com.mojang.blaze3d.vulkan.VulkanDevice` on **Apple M4 Pro** (vendor APPLE);
   - non-zero `VkInstance`, `VkDevice` and VMA allocator handles;
   - queue families **graphics=0, compute=3, transfer=3** — the graphics and compute
     queues are *different families*, which matters for any compute work Voxy submits
     alongside Minecraft's graphics work (queue family ownership transfers, or the
     compute queue's own submission);
   - main render target colour `Main / Color` **RGBA8_UNORM** 1708x960, depth
     `Main / Depth` **D32_FLOAT** 1708x960, both with non-zero `VkImage`/`VkImageView`
     and usage `0xf`;
   - no notes, i.e. nothing had to be guessed or skipped.

So the device and its targets are reachable with no OpenGL context involved. What is
still absent is any Voxy rendering: Voxy disables itself on this backend (it logs that
it needs a GL context to composite through), which is the next piece of work, not a
property of the environment.

`PreferredGraphicsApi.VULKAN.getBackendsToTry()` returns `[VulkanBackend, GlBackend]`,
so Vulkan is tried first and OpenGL remains the fallback; `VulkanBackend.checkBackendAvailable()`
rejects the backend when the Vulkan loader library is missing or
`glfwVulkanSupported()` is false, before it ever creates an instance.

## The bounded draw, measured (2026-10-04)

`McNativeMarkerDraw` records one bounded draw — a small magenta quad, no descriptors, no
push constants, no vertex buffers — using a pipeline Voxy builds **on Minecraft's own
device**, into the command buffer of a render pass opened through **Minecraft's own**
`CommandEncoder.createRenderPass`, so every layout transition and barrier for the colour
attachment stays Minecraft's. Destruction goes to `queueForDestroy`. It is off unless
`-Dvoxy.native.marker=true`.

What the run shows (`scripts/verify.py --only native`, Minecraft's own validation layers
enabled through `--vulkanValidation`; evidence
`build/harness/20261004T064301-067161Z/summary.json`, which passed with
`changed_sources_during_run: []`, so the sources it fingerprinted are the ones measured):

- the pipeline is created on Minecraft's device for its actual colour format
  (`VK_FORMAT_R8G8B8A8_UNORM`, 37) and stays live across the whole lifecycle;
- **4800 draws** recorded into Minecraft's command buffers over the eleven-checkpoint
  scenario, with **zero validation diagnostics** and no notes — recording raw `vkCmd*` into
  Minecraft's pass, and leaving the barriers to Minecraft, is accepted by the validation
  layer on MoltenVK;
- the marker is **present in the captured frames**: ten of eleven checkpoints show the full
  box (15088 pixels at 1708x960, 19136 at 1920x1080), the eleventh being a frame with no
  level content (below);
- the whole stage — environment gate, marker gate, zero diagnostics, validation-layer loader
  evidence — passes as one run, and still reports `voxy_integration_status:
  BLOCKED_UNIMPLEMENTED`, because a marker is not terrain.

Three facts that must not be assumed, each measured rather than reasoned:

1. **The composed frame is y-flipped relative to Vulkan NDC.** A quad at NDC y = -0.98,
   which is the top in plain Vulkan, appeared at y ≈ 854 of a 960-pixel image — the bottom.
   The shader's y sign is therefore chosen from this measurement, not from the convention.
2. **Minecraft's composition does not preserve colour exactly.** The shader writes
   (255, 0, 255); the screenshots show (255, 0, 255) in most scenes but (239, 0, 239),
   (242, 0, 242) and (253, 0, 253) in others. An exact-colour pixel oracle on Minecraft's
   composed frame is therefore invalid; the gate checks a bounded neighbourhood and records
   the value it actually found.
3. **Not every captured frame contains a rendered level.** The nether checkpoint's frame was
   essentially black (the whole marker box was (5, 2, 2)); the level simply was not rendered
   on the frame that was captured, so the hook never ran. The gate records such a frame as
   "no level content" instead of counting it for or against the draw, and separately requires
   most checkpoints to be real so a run cannot pass by calling every frame empty.

What this does NOT show: any Voxy terrain. The draw is a marker, chosen so that "did our
command reach Minecraft's frame" is answerable without dragging in depth conventions,
descriptors or the terrain pipeline. Depth is deliberately not attached.

## What is NOT answered yet, and must be measured on hardware

1. **Image-state ownership** — partly answered. Opening the pass through Minecraft's
   own `createRenderPass` leaves every colour transition to Minecraft and is validation
   clean, so Voxy does not need to know the layout for that route. What remains unverified
   is the route where Voxy records into a pass Minecraft itself opened (Sodium's terrain
   pass), and anything involving the depth attachment.
2. **What may be recorded where.** Compute cannot be recorded inside an open
   dynamic-rendering pass, so the traversal/cull/table work must be placed before
   the pass in a separate command buffer, with a barrier chain that MC's pass then
   observes. The exact placement relative to Sodium's own passes is unverified.
3. **MoltenVK coverage** of the paths MC relies on (`vkCmdDrawMulti*EXT`,
   push descriptors) and of Voxy's own indirect/compute usage on MC's device.
4. **Descriptor interplay** between Voxy's own layouts and MC's push-descriptor
   usage inside the same pass.
5. ~~Whether the preference actually selects this backend on this host~~ — answered
   above: only the launch argument does, and `getBackendDescription()` reports the LWJGL
   version rather than the API, so the backend's identity must come from the device
   class (as the probe does), never from that string.

## Proposed first step (bounded, evidence-first)

Add a diagnostic that, when Minecraft runs its Vulkan backend, reaches the backend
through a mixin accessor and reports: `getBackendDescription()`, the `VkDevice` /
queue family indices, `DeviceInfo`, and the `vkImage`/`vkImageView` handles plus
format and extent of the main render target's colour and depth. Then record one
bounded Voxy draw into Minecraft's command buffer at the Sodium terrain hook and
capture the frame, with the validation layer enabled, retaining the diagnostics.

That answers (1), (2) and (5) with real evidence before any controller or resource
code is adapted, and it is the smallest change that can prove a real connection
rather than a preference setting.
