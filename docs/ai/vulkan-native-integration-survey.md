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

## Can Voxy's shaders run on Minecraft's device? Measured (2026-10-04)

This is the question that decides whether the renderer can be adopted onto Minecraft's
device at all, so it was measured before writing any adoption code.

`McNativeFeatureAudit` asks two separate questions per feature `VkContext` requests —
does the physical device support it, and did Minecraft enable it — because the answers
have different consequences. Result on this host
(`build/harness/20261004T073531-523575Z`):

- **nothing Voxy needs is unsupported.** MoltenVK 1.4.2 on Apple M4 Pro supports all
  eight: `multiDrawIndirect`, `drawIndirectFirstInstance`, `shaderInt64`,
  `fragmentStoresAndAtomics`, `vertexPipelineStoresAndAtomics`, `shaderDrawParameters`,
  `synchronization2`, `dynamicRendering`.
- **Minecraft enables only half of them.** Its `REQUIRED_DEVICE_FEATURES` are
  `multiDrawIndirect`, `fillModeNonSolid`, `samplerAnisotropy`, `shaderDrawParameters`,
  `timelineSemaphore`, `hostQueryReset`, `synchronization2`, `dynamicRendering`,
  `vertexAttributeInstanceRateDivisor`, `multiDraw`; its device extensions are
  `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`,
  `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain` — note that
  `VK_EXT_metal_objects` is **not** among them, so the native path has no IOSurface
  import available and does not need one.
- So `drawIndirectFirstInstance`, `shaderInt64`, `fragmentStoresAndAtomics` and
  `vertexPipelineStoresAndAtomics` are supported-but-not-requested. That gap is in
  Minecraft's device creation, not in the hardware.
- `DeviceInfo.isZZeroToOne()` is **true**, matching Voxy's `USE_ZERO_ONE_DEPTH`.

`MixinVulkanBackend` closes the gap by adding those four to the feature set Minecraft
passes to `vkCreateDevice`, behind `-Dvoxy.native.features=true` so a normal game's
device creation is untouched, and with `require = 0` so a changed internal does not
crash the game.

**A warning worth keeping.** The first implementation derived the feature offsets
arithmetically from one of Minecraft's own features. `VulkanFeature`'s offset turned out
to be relative to the `features` member of `VkPhysicalDeviceFeatures2`, not to the struct
start, and Minecraft's own numbers read correctly under *either* interpretation — so the
derivation looked sound and was wrong by 16 bytes. It requested `depthBounds`,
`shaderStorageImageMultisample` and `sparseBinding`, which MoltenVK does not support, and
**Minecraft refused to start** (`VK_ERROR_FEATURE_NOT_PRESENT`, "vkCreateDevice(): the
15th flag ... is not available"). The offsets are now resolved by experiment: each
candidate is written into a scratch `VkPhysicalDeviceFeatures2` and read back through
LWJGL's own accessor, and a feature is only added if exactly the intended field became
set. A feature whose offset cannot be verified is not added.

**The proof that the features are actually enabled.** Vulkan has no API for "which
features are enabled on this device", so requesting them is not evidence. A minimal
compute shader — `uint64_t` written into an SSBO, which needs both `shaderInt64` and
compute storage writes — is compiled by Voxy's own `SpirvCompiler`, built into a pipeline
on Minecraft's `VkDevice`, dispatched on Minecraft's **compute queue** (family 3 here,
separate from graphics family 0) from Voxy's own command pool, waited on with a fence, and
read back: `0x0123456789abcdef` written, `0x0123456789abcdef` read. In the same run the
eleven-checkpoint lifecycle, the marker draw and the environment gate all pass with zero
validation diagnostics.

So Voxy's shader feature requirements can be satisfied on Minecraft's device, and the
remaining work for terrain is Voxy's own: `VkContext` must be able to adopt an external
instance/device/queues/allocator instead of creating them.

## Adoption, depth and the queue split, measured (2026-10-04)

`VkContext` can now be handed an external instance/physical device/device/queue instead of
creating its own (`VkContext.initAdopted`, `adopted = true`). The borrowed objects are
never destroyed: `destroy()` frees only the command pool it created, and skips
`vkDestroyDevice`/`vkDestroyInstance` — destroying either would kill Minecraft's own
renderer. Derived state (limits, memory properties, subgroup size, timestamp bits, unified
memory type, device extensions) is re-queried from the given physical device, so Voxy's
existing `VkBuffer`/`VkTexture`/shader/pipeline code behaves the same in both modes.
`McNativeVkContext` wires this up behind `-Dvoxy.native.adopt=true`, from the head of
`VoxyClient.initVoxyClient` so it lands before Voxy would create a device of its own.

**Queue choice.** Adoption takes Minecraft's **graphics** queue (family 0 here) and the
constructor verifies that family has both GRAPHICS and COMPUTE. A graphics-capable family
must support compute, so Voxy's traversal/cull/table-generation passes can share the one
queue and no cross-family ownership transfer is needed — which matters here, because this
host's compute queue is a *different* family (3).

**Proof that Voxy's own layers work on Minecraft's device**
(`build/harness/20261004T144320-209089Z`): a Voxy `VkBuffer` (persistently mapped through
`VkContext.findMemoryType`), a compute shader from Voxy's own `SpirvCompiler`, and Voxy's
own command pool and queue — wrote and read back `0x0123456789abcdef`
(`provenByVoxyBufferAndShader: true`). That is the buffer layer, the shader layer and the
queue/pool layer all running on Minecraft's device.

**Depth.** The marker draw now attaches Minecraft's depth view (`D32_SFLOAT`, format 126)
to the pass it opens through Minecraft's API, and proves depth test *and* depth write
without assuming anything about Minecraft's scene-depth convention:

1. a base quad over the whole box with compare `ALWAYS` + depth write (cyan, z = 0.6);
2. a nearer quad over the left 60% with compare `LESS` (magenta, z = 0.3) — must pass;
3. a farther quad over the whole box with compare `LESS` (yellow, z = 0.9) — must be
   rejected everywhere.

Measured across the lifecycle: near ≈ 9200 px, far 5888 px (a 60/40 split, as the geometry
says), and **rejected 0 px in every checkpoint**. A single pixel of the third colour would
mean depth testing did not happen, so the gate fails on it.

**Lifetime, corrected.** The first adopted run ended with a *correct* validation complaint:
"All child objects created on device must have been destroyed ... prior to destroying
device". In adopted mode the device belongs to Minecraft, so Voxy's own command pool and
pipelines must go first; `ClientLifecycleEvents.CLIENT_STOPPING` now calls
`McNativeVkContext.releaseAdopted()`, and the run is clean (zero diagnostics).

**One more y-orientation trap.** The quad rectangle constants hold the sign the measurement
established, and an early version negated y *again* inside the draw call — putting the box
back at the bottom of the image where the gate was not looking, with everything else
working. The depth proof was in fact already correct in that run (near 9730 / far 6528 /
rejected 0 at y ≈ 854). The negation now happens in exactly one place.

## Voxy's real shader stack on Minecraft's device, measured (2026-10-04)

The earlier proofs used pipelines assembled on the spot. This one runs **Voxy's production
machinery**: `VkShaderLoader.parse` (including `#import <voxy:lod/vk/quad_index.glsl>`
resolution and define injection), `VkAutoBindingShader` (descriptor sets derived from the
SPIR-V's own bindings), `VkFrameTracker` (Voxy's frame and command-buffer management),
`VkBarriers` (synchronization2 translation), and `VkBuffer`/`VkTerrainResources`/
`SyntheticTerrain`. The shader is `index_probe.comp`, a real one that calls the *same*
`resolveQuad` the vertex shader uses, for every quad ordinal.

Result (`build/harness/20261004T150924-204694Z`, OVERALL true,
`changed_sources_during_run: []`, **zero validation diagnostics**): **189 quad ordinals
resolved on Minecraft's device, every one matching the CPU linear-search reference**, with
the adoption proof, the marker draw, the depth proof and the eleven-checkpoint lifecycle all
green in the same run.

Getting there required three fixes, each a real finding rather than a slip:

**1. Core 1.3 entry points are not the same as the KHR ones.** synchronization2 and
dynamic rendering became core in Vulkan 1.3, but Minecraft's device is 1.2 with
`VK_KHR_synchronization2` and `VK_KHR_dynamic_rendering`. Functionally identical, *different
function pointers* — the core ones are null there, and LWJGL's `Checks.check` throws
`NullPointerException` rather than anything that names the cause. Voxy called the core
entries directly, so its barrier layer died the moment it ran on Minecraft's device. The new
`VkCmd` picks core or KHR from the command buffer's own device capabilities for the three
commands involved (`vkCmdPipelineBarrier2`, `vkCmdBeginRendering`, `vkCmdEndRendering`; 22
call sites). Structs and `sType` values are shared between core and KHR, so nothing else
changes, and Voxy's own 1.4 device still takes the core path — the 313-test suite is
unchanged. **Any new native-path code will hit this, which is why the choice lives in one
place.**

**2. Destruction order belongs to whoever owns the device.** In adopted mode Minecraft owns
it, so everything Voxy created must go first, and it must go *after* Minecraft's last frame
has retired. Two separate validation complaints, both correct, taught this: pipelines
destroyed while submitted commands still referenced them, and child objects outliving the
device. `releaseAdopted()` now waits for the device to idle (safe at client stop), destroys
the marker draw immediately rather than deferring it to `queueForDestroy` — which nothing
would ever process at shutdown — and empties Voxy's static GPU caches (`VkCullPass`,
`VkUploadStream`, `VkDownloadStream`, `VkSampler`, `VkQuadIndexBuffer`, `VkFrameTracker`),
exactly the list the Vulkan tests tear down.

**3. The probe needed to be runnable without Minecraft.** The first in-game attempt threw an
NPE whose cause was invisible from the outside, and each diagnosis cost a full client launch.
`McNativeRealShaderProbe.runAgainstCurrentContext()` is therefore callable against *any*
`VkContext`, and `McNativeRealShaderProbeTest` runs it against Voxy's own device in the
ordinary suite. That separates "the probe is wrong" from "Minecraft's device is different",
and it is what located the KHR problem in one iteration instead of several.

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
