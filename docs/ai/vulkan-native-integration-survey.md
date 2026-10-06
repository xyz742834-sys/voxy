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
a run that passed with
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
(measured that day; the evidence retained in the repository is [native-evidence](runs/native-evidence/), written by the run that still passes today's stricter gates):

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
(measured that day; see [native-evidence](runs/native-evidence/) for the retained proof files): a Voxy `VkBuffer` (persistently mapped through
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

Result (measured that day, OVERALL true,
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

## Round-1 review repairs (2026-10-05)

The round-1 reviewer returned REDESIGN with five blocking findings
([native-integration-review-r1.md](runs/native-integration-review-r1.md)) and, rather than
arguing, encoded counterexample PNGs and showed the real gate functions accepting frames
that proved nothing. What changed:

**The screenshot was never the right place to look.** Voxy records at the end of level
rendering, so Minecraft's GUI, a loading overlay and post-processing all come afterwards and
can hide the marker — measured on the nether checkpoint, whose frame showed nothing although
the draw had certainly run. The authoritative proof is now a **readback of Minecraft's own
colour image** taken right after the draw, through Minecraft's own
`CommandEncoder.copyTextureToBuffer` so the layout transitions stay Minecraft's.

**The readback does not assume an orientation either.** The first version read the box's
rectangle using final-image coordinates and found nothing at all: the texture's rows run
opposite to the composited frame. Guessing which way up it is was the wrong fix. It now reads
the whole image once and judges **relationships**: the near and far quads must be side by
side across the same rows, and the rejected colour must exist (in its control strip) but
never inside the rows the depth-tested box occupies. Those hold whichever way the rows run.

**A third draw that is simply absent now fails.** "No rejected colour in the box" was treated
as proof that depth rejected it, which it is not — not issuing the draw looks identical. The
marker therefore also draws the rejected colour in a control strip with compare `ALWAYS`, and
the gate requires it there.

**The proof files are gated.** `native-device-features.json`, `native-compute-probe.json`,
`native-adopted-context.json` and `native-real-shader.json` were written and never read, and
probe failures log at INFO/WARN rather than the ERROR level the stage rejects — so a failed
adoption passed. Each is now required, with its claim checked, and the adopted device must be
the device the lifecycle checkpoints observed (compared numerically: the probes spell handles
as hex, the checkpoints as integers).

**Nothing native runs without its own flag.** The probe ran unconditionally, and
`releaseAdopted` queried Minecraft's device and called `vkDeviceWaitIdle` *before* checking
whether anything had been adopted — an ordinary Vulkan-backend player could stall at exit for
a diagnostic they never enabled. The probe now has `voxy.native.probe`, and release returns
immediately unless the context was adopted.

**Nothing is destroyed on an unconfirmed wait.** All three fence waits previously fell through
to destruction on timeout, while submitted commands might still reference the objects. They
now leak deliberately and say so; marker retirement no longer falls back to immediate
destruction when `queueForDestroy` refuses.

**The evidence lives in the repository.** Every claim above cited paths under `build/`, which
is ignored — so for anyone else the evidence did not exist. The native stage now copies its
JSON proofs into `docs/ai/runs/native-evidence/<run>/` and records each screenshot's sha256
in a manifest. The passing run is
[20261005T050713-618859Z](runs/native-evidence/20261005T050713-618859Z/MANIFEST.json), whose
readback reports near 9888, far 6528, control 3249, rejected-inside-the-box 0, no relationship
problem, with every gate green and zero validation diagnostics.

Smaller repairs: the type-based reflection refuses to guess when a class has more than one
field of the wanted type and ignores statics; the command-buffer cache is keyed by backend
class; physical-device selection matches Minecraft's reported device name instead of taking
the first enumerated one; `hasMetalObjects` in adopted mode comes from the extensions
Minecraft actually enabled rather than from physical support; and the partial PNG decoder
carries its own CRC, IEND, dimension and row-count bounds.

The counterexamples the reviewer used are now tests (`scripts/tests/test_marker_gate.py`,
47 Python tests in total), so the gate cannot drift back.

## A note on how runs are cited

`build/` is ignored, so a run id on its own cites nothing that anyone else can open. The
measurements below were made on the dates given; what is checkable is the evidence the runner
retains in the repository under [native-evidence](runs/native-evidence/) — proof files, the
stage log, the finished summary, the source fingerprint, the candidate revision, a manifest of
screenshot hashes and a crop of the marker region from every captured frame. Where an older
paragraph describes a run whose output is gone, the claim stands only insofar as a later
retained run reproduces it under stricter gates.

## Round-2 review repairs (2026-10-06)

Round 2 ([native-integration-review-r2.md](runs/native-integration-review-r2.md)) closed B2
and judged the work safe for ordinary play, but returned REDESIGN on four sharper findings.
What changed:

**The readback now checks position and density, not colour spans.** The reviewer defeated the
previous version with three distant 500-pixel patches: taking each colour's bounding box
across the whole image, unrelated scene pixels satisfied the relationships. The check now
measures the *expected rectangles* — near over the left 60% of the box, far over the rest, the
rejected colour over its cell and its control strip — and requires each to be at least 80%
filled, with `rejectedInBox` **counted in the box** rather than inferred. Orientation is still
not assumed: both the composited orientation and its vertical mirror are measured, and the one
that satisfies the requirements is taken.

**The depth-TESTED rejected draw is now provable.** A control strip drawn with compare `ALWAYS`
only shows that the colour can be drawn; it does not show that the depth-tested draw was ever
issued, because not issuing it looks the same. The marker therefore draws the rejected colour
twice with the *same depth-testing pipeline*: into a cell where its depth is nearer than the
base (so it must pass) and into the box where it is farther (so it must be rejected). Both
outcomes from one pipeline and one colour is what makes "issued, then rejected by depth" a
measurement rather than an inference.

**One readback was stale for the rest of the run.** It now repeats every 240 draws and the
gate requires at least three clean readbacks and zero problems; the passing run reports
**20 clean, 0 problems**. A frame showing the rejected colour in the box is also no longer
excusable for any reason the implementation gives.

**The proof files must agree on one identity.** Requiring them was not enough: a missing
adopted device skipped the comparison, and the marker/probe files' own identities were never
read — a fixture with a null device and a mismatch passed. Every field the gate relies on must
now be present and of the right type, and the adopted, marker, probe and lifecycle-checkpoint
devices must all be the same handle (compared numerically, since the probes spell them in hex).

**Nothing is destroyed after an unconfirmed wait, anywhere.** The real-shader cleanup destroyed
after a drain it had not confirmed; `VkFrameTracker.destroy` ignored its `vkDeviceWaitIdle`
result; marker shutdown destroyed when Minecraft's device was unreachable, and retirement
queued a draw against a *replaced* device. All four now leak deliberately and say why.

**The evidence can be replayed.** It keeps the stage log, the finished summary, the source
fingerprint, the candidate revision and worktree state, every proof file, a manifest of
screenshot hashes, and a small PNG crop of the marker region — twelve files for the passing
run [20261006T064519-555551Z](runs/native-evidence/20261006T064519-555551Z/MANIFEST.json).

Also: the duplicated reflection helpers now share the strict one that refuses ambiguity and
ignores statics; the compute probe and feature audit no longer fall back to the first physical
device (if none is named as Minecraft's, they do not measure); the readback carries a 40 MiB
budget rather than allocating 126 MiB at 8K; and the buffer is closed if the copy is never
registered.

One bug of my own is worth recording because the gate caught it: rearming the readback by
subtracting from `Long.MIN_VALUE` overflowed, so it never ran — and the gate failed the run
with "Minecraft's colour image was never read back" instead of quietly reporting success.

## Round-3 review repairs (2026-10-06)

Round 3 ([native-integration-review-r3.md](runs/native-integration-review-r3.md)) returned
REDESIGN on four residuals — each one opening by confirming the round-2 repair and then naming
what it did not cover.

**The rejected draw is now one command, not two.** The pass cell was a *separate* draw, so it
showed that the colour and pipeline work, never that the box-targeted command was issued —
omitting that command looks the same. The rejected colour is now drawn by a **single quad
spanning the box and the cell**, with the base depth differing by region (the box's base is
nearer, the cell's is farther). One command, two outcomes decided purely by depth: the cell
being full proves it was issued, the box being empty proves depth rejected it.

**Evidence is persisted the moment a readback finds a problem**, instead of only every 600
draws, where an intermediate failure could be overwritten by the next clean sample.

**Every proof names its device.** The compute and real-shader proofs published no identity at
all, so a contradictory one was ignored. Five identities — adopted, marker, probe, compute,
real-shader — must now agree with each other and with the lifecycle checkpoints, and
`attempted` and `firstMismatch` are checked rather than assumed.

**Release no longer destroys across a device change.** If Minecraft's device is replaced, the
adopted context holds the old one while the marker follows the new one; release waited on the
old and destroyed the new. `shutdownImmediate` now takes the device whose idle was observed and
refuses to destroy anything that does not belong to it.

**The pixel gate can be replayed.** One crop could not do that, and the retained summary was
the pre-finalization copy. The runner now keeps a crop of the marker region from **every**
captured frame, at both ends of the image since either can hold it, and retention happens after
the final save so the summary includes the finished verdict and the source-change check. The
passing run is [20261006T070311-099413Z](runs/native-evidence/20261006T070311-099413Z/MANIFEST.json):
33 files, 22 crops, 704 KB in total, readback near 12420, far 8316, cell 4224,
rejected-in-box 0, 15 clean readbacks and no problems, every gate green, zero validation
diagnostics.

## Round-4 review repairs (2026-10-06)

Round 4 ([native-integration-review-r4.md](runs/native-integration-review-r4.md)) closed B5 and
returned REDESIGN on B1, B3, B4 and one new low finding. Its verdict bounds what may be claimed
from here: *"The diagnostic layer is not yet sound enough to use as the accepted foundation for
terrain work... Terrain investigation can be experimental; it should not be promoted as
continuation from an independently accepted diagnostic layer."* That sentence stands until an
independent round says otherwise — these repairs are offered to round 5, not as a self-granted
acceptance.

**The gate no longer checks numbers the implementation produced.** This was the real content of
B1, and it had survived three rounds of narrower repairs: the readback aggregates were the whole
proof, the screenshots were only supporting, and nothing outside the implementation ever saw the
pixels those aggregates came from. The implementation now retains the **raw pixels it measured**
— the colour image's crop covering the box, the pass cell and the control strip — as a PPM,
together with the orientation it selected, the draw count at the moment of capture, and the
crop's rectangle in full-image coordinates. `recount_marker_sample()` in `scripts/verify.py`
reads that sample back, resolves the published NDC geometry into it on its own, counts each
region itself, applies the density requirement, and *then* requires the implementation's
aggregates to equal its own counts. A missing sample fails; disagreeing counts fail. The gate's
conclusion now rests on pixels, with the aggregate as a cross-check rather than the evidence.

**Failures persist their own evidence and are counted.** B4's residual was that the exception
paths — a readback that could not be requested, a map or classify that threw — reported through
`note()` only: they neither incremented the problem counter nor wrote the evidence file, so disk
kept the previous clean sample and the run looked unbroken. All of them now go through one
`failReadback()`, which records a non-completed readback carrying the reason, counts the problem,
and writes the evidence immediately. The evidence it writes states the target size the draw
actually saw rather than zeros, and the gate rejects a non-positive size so that path cannot
produce a report whose regions are all degenerate.

**Proof files must not contradict themselves.** Beyond the five device identities from round 3,
the gate now checks that the feature injection states `attempted` and that each of its notes
records a read-back verification rather than an assumption, and that the adopted-context proof
states `enabled` and the value it read back. A proof that was never attempted, or that read back
something other than what it wrote, fails instead of passing on its summary field.

**Readbacks are bounded and abandoned rather than retried forever** (R4-L1). Only one may be in
flight; after three problems the implementation stops requesting them instead of re-issuing
every 240 draws; buffers over 40 MiB are refused rather than allocated; and the number of
readback buffers that could not be closed is published instead of swallowed, since each one is a
frame's worth of memory held.

The gate's own ordering was wrong as well: a density shortfall was reported before the
rejected-colour check, so a depth failure could be described as a thin quad. The order is now
geometry, readback field types, rejected colour in the box, density, then the independent recount.

**The retained evidence replays, and losing it fails the run.** Round 4 called the committed
gates directly on the retained directory and they returned false for want of the full
screenshots — which are tens of megabytes and were never the authoritative part. Two repairs
follow from that. First, the raw colour samples are now retained alongside the proof files and
crops, and each crop records its parent size and origin rather than leaving the reader to
reconstruct placement from the recording constants. Second, `scripts/verify.py
--replay-evidence <dir>` re-checks a retained directory on its own and launches nothing: it
verifies every manifest hash, recounts the raw colour sample, and re-runs the proof-file
consistency gate against the checkpoints in the retained summary — and it prints what it is
*not* replaying, so the screenshot measurement is named as unreplayable rather than implied to
be covered. Retention failure also no longer passes quietly: if the raw sample cannot be kept,
the stage and the run fail, because the evidence is part of the claim.

The passing run is
[20261006T073859-396220Z](runs/native-evidence/20261006T073859-396220Z/MANIFEST.json): 51 files,
22 crops, 18 raw colour samples, 2.2 MB in total; readback near 12420 of 12420, far 8316 of
8316, cell 4224 of 4224, rejected-in-box 0, 15 clean readbacks, no problems, no unclosed
readback buffers; the independent recount of `native-marker-sample-3363.ppm` agrees with every
one of those figures; every gate green and zero validation diagnostics. `--replay-evidence` on
that directory returns 0 with all manifest hashes matching.

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
