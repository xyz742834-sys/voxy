# Native Minecraft-Vulkan integration — API survey against the actual candidate

Investigation for the next priority in [project-goal.md](project-goal.md), done
2026-10-04 against the **actual** candidate this project builds on:
`minecraft_version=26.2`, `fabric_api 0.152.2+26.2`,
`sodium mc26.2-0.9.2-alpha.3-fabric`. Every signature below was read from the
class files in those artifacts (`javap` on
`~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` and the Sodium jar), not
from the historical [device-sharing survey](../phase6-device-sharing-survey.md),
whose assumptions this replaces where they differ.

This was the API basis for deciding the first step; the dated sections below record what has
been implemented as diagnostics since, and what each review accepted or refuted.

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

> ⚠ **Dated paragraph, superseded by the code.** When this was written the draw was *one*
> magenta quad with no push constants. Rounds 2 and 3 replaced it with **six** quads carrying
> a 36-byte push payload (base, near, the spanning rejected quad, the control strip). Read the
> source for what is recorded today; this paragraph records what was measured on 2026-10-04.

`McNativeMarkerDraw` records a bounded draw — then a small magenta quad, no descriptors, no
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
3. **Not every captured frame shows the draw.** The nether checkpoint's frame was
   essentially black (the whole marker box was (5, 2, 2)).
   ⚠ **Why is not established.** The earlier text here said "the level was not rendered, so
   the hook never ran"; round 5 refuted that as causality — neither a dark frame nor a rising
   cumulative draw counter says what happened in the frame that was captured. What is measured
   is only that the frame carried nothing. The gate therefore records such a frame as
   "no marker" without claiming a reason, counts at most three of them, and requires at least
   eight captured frames to carry the marker, so a run cannot pass by calling every frame
   empty. The proof the gate rests on is the readback, not these frames.

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
screenshot hashes and a crop of the marker region from every captured frame.

**A retained directory stops replaying when the gate gets stricter, and that is expected.**
Every repair round adds required fields, and a run recorded before them cannot satisfy them.
The retained files stay readable and their figures stay checkable by hand, but the claim
"`--replay-evidence` returns 0" is only ever about the newest run. Older directories are kept
deliberately — deleting them would turn their measurements into narrative, which is the opposite
of the point — and the replay status of each is:

| Run | Replays under the current gate (2026-10-09, evening) |
| --- | --- |
| [20261006T070311-099413Z](runs/native-evidence/20261006T070311-099413Z/MANIFEST.json) | no — predates raw-sample retention entirely |
| [20261006T073859-396220Z](runs/native-evidence/20261006T073859-396220Z/MANIFEST.json) | no — no `leakedPipelines` counter |
| [20261006T080931-279390Z](runs/native-evidence/20261006T080931-279390Z/MANIFEST.json) | no — no `leakedPipelines` counter |
| [20261006T083136-838975Z](runs/native-evidence/20261006T083136-838975Z/MANIFEST.json) | no — the rejected orientation's pixels were not retained (round 6 B4) |
| [20261006T084300-332469Z](runs/native-evidence/20261006T084300-332469Z/MANIFEST.json) | no — the rejected orientation's pixels were not retained (round 6 B4) |
| [20261006T095436-054038Z](runs/native-evidence/20261006T095436-054038Z/MANIFEST.json) | no — its depth report predates `zConventionMeasuredHere` (round 7) |
| [20261006T165039-062378Z](runs/native-evidence/20261006T165039-062378Z/MANIFEST.json) | no — its source fingerprint is not this checkout's inventory (round 11) |
| [20261007T005606-024013Z](runs/native-evidence/20261007T005606-024013Z/MANIFEST.json) | no — its ladder report predates the per-pixel schema (`palette`, `samples`); round-8 refuted that ladder's inference |
| [20261007T011026-211934Z](runs/native-evidence/20261007T011026-211934Z/MANIFEST.json) | no — same schema; kept as the measurement that showed non-zero depth over clouds |
| [20261007T020025-032770Z](runs/native-evidence/20261007T020025-032770Z/MANIFEST.json) | no — its samples predate the frame thumbnails round 9 asked for; kept as the first per-pixel measurement (18 samples, counts confirmed by round 9) |
| [20261007T022908-162255Z](runs/native-evidence/20261007T022908-162255Z/MANIFEST.json) | no — its source fingerprint is of the tree before the round-10 repairs, which the replay now requires to match the checkout (round 10 confirmed its 18 samples) |
| [20261007T025423-410921Z](runs/native-evidence/20261007T025423-410921Z/MANIFEST.json) | no — predates the published pipeline states; its fingerprint is of the tree before the round-11 repairs (round 11 confirmed its 18 samples) |
| [20261007T031630-708042Z](runs/native-evidence/20261007T031630-708042Z/MANIFEST.json) | no — its fingerprint is of the tree before the Z-direction stages (round 12 confirmed its 19 samples) |
| [20261009T033539-494735Z](runs/native-evidence/20261009T033539-494735Z/MANIFEST.json) | no — its log lines predate the stage/camera reconciliation and its fingerprint is of the tree before the round-13 repairs (round 13 confirmed its direction) |
| [20261009T041151-205062Z](runs/native-evidence/20261009T041151-205062Z/MANIFEST.json) | no — predates the coexistence experiment and the fourth pipeline state (round 14 confirmed its direction) |
| [20261009T045515-905630Z](runs/native-evidence/20261009T045515-905630Z/MANIFEST.json) | no — predates the exact-RGB coexist rule and the required-evidence gate (round 15 confirmed its 24 samples) |
| [20261009T060453-272917Z](runs/native-evidence/20261009T060453-272917Z/MANIFEST.json) | no — predates the launch-token semantics repair (round 16 confirmed its 24 pairs) |
| [20261009T063708-667962Z](runs/native-evidence/20261009T063708-667962Z/MANIFEST.json) | no — predates the terrain-LOAD experiment, the literal-token requirement and the request-time log line (round 17 confirmed its 24 pairs) |
| [20261009T073850-255416Z](runs/native-evidence/20261009T073850-255416Z/MANIFEST.json) | no — predates native instance mode (round 18 judged it) |
| [20261009T080812-119439Z](runs/native-evidence/20261009T080812-119439Z/MANIFEST.json) | no — predates the pinned terrain-LOAD view, the camera capture and the `horizon` stage |
| [20261009T085529-037358Z](runs/native-evidence/20261009T085529-037358Z/MANIFEST.json) | no — predates real-LOAD and the complete instance inventory (round 19 judged it) |
| [20261009T100957-766731Z](runs/native-evidence/20261009T100957-766731Z/MANIFEST.json) | **yes** — the only run built from this checkout's sources; Z direction, coexistence, terrain-LOAD, instance mode and real-LOAD judged |

**Any figure from a run whose evidence directory is not in the repository is narrative, not
proof.** Round 5 made this explicit: it could confirm the mechanisms and the figures of the
retained run, and it refused every exact count, failure and causal explanation attributed to a
run whose output is gone — correctly, since nothing can be re-measured from an absent
directory. So: the citable numbers in this document are the ones in
[native-evidence](runs/native-evidence/). The dated paragraphs are kept because the sequence of
mistakes is useful, not because their numbers can be checked. Where an older paragraph
describes a run whose output is gone, the claim stands only insofar as a later retained run
reproduces it under stricter gates.

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
one of those figures; every gate green and zero validation diagnostics.

⚠ `--replay-evidence` returned 0 on that directory **when this was written**. It does not any
more: the round-5 repairs made the gate require counters this run never published
(`leakedPipelines`), so replaying it now fails with "the readback does not state
leakedPipelines". That is the gate getting stricter, not the run getting worse — but it means
the only directory whose replay can be cited is the newest one. See
[A note on how runs are cited](#a-note-on-how-runs-are-cited).

## Round-5 review (2026-10-06)

Round 5 ([native-integration-review-r5.md](runs/native-integration-review-r5.md)) returned
REDESIGN. It confirmed the mechanisms and refused the completeness: raw pixels, retained crop
origins, the no-launch replay and several failure checks are real, and B1, B3, B4 and R4-L1 are
all still open on narrower residuals. It also found a **new blocking lifetime defect** the
earlier rounds had missed, and its acceptance boundary is unchanged: *"an independently accepted
diagnostic foundation is still REDESIGN."* That sentence governs this document.

What it broke, in its own constructions:

- **The recount can accept an incomplete proof.** `recount_marker_sample` intersected each
  expected region with the producer's own `sampleRect` and then used *that* intersection as the
  density denominator. Removing the leftmost 20 columns of the retained sample and adjusting
  `sampleRect` and two counts to match gave `near=10260/10260`, `far=8316/8316`,
  `cell=3784/3784` — 100% dense, with the omitted columns free to be yellow throughout.
- **`--replay-evidence` rechecked almost nothing.** It called the recount and the proof-file
  helper directly, bypassing the marker gate. A report with `attempted=false`,
  `completed=false`, `timesClean=0`, `timesWithAProblem=3`, `enabled=false`, `drawsRecorded=0`,
  `depthAttached=false` and `closeFailures=99` still replayed as **0**, as did a directory with
  `MANIFEST.json` removed entirely.
- **Retention could lose the sample it referenced.** The check was "at least one sample exists",
  so duplicating a sample and deleting the referenced one left retention reporting no error.
- **Contradictory proofs still passed.** Adoption's `attempted` was never read; the feature
  notes were a substring match with no one-to-one coverage of `added`, so an empty list passed
  vacuously and `"FAILED: not verified by read-back"` passed because it contains the phrase;
  `expected=""` and `readBack=""` compared equal.
- **Disk could still hold a clean proof over a failure.** With `instance` cleared, a failing
  callback wrote nothing. A PPM write failure returned the *original successful* readback, so
  the clean counter advanced and no failure was published. A `close()` failure incremented only
  its own counter. The registration-path close exception was counted nowhere.
- **Trying both orientations does not identify the right one.** Fed an image with a correct
  pattern at the top and an entirely yellow box at the bottom, the classifier chose the top and
  reported no problem. Density rejects sparse noise; it cannot say which of two dense patterns
  is the marker.
- **The sample is not bound to its capture.** `measure` reads the global draw counter when the
  callback runs, not when the copy is registered, so the file name can disagree with the
  request; and the gate neither required nor bounded `sampleAtDraw`.
- **R5-LIFETIME (new blocker).** `retire(draw, device)` cleared only the local variable, not
  the static `instance`. If the replacement `create` returned null, the retired draw stayed
  reachable, and if the target format later returned to its old value the mismatch branch was
  skipped and `record` ran on an object already queued for destruction. The reviewer reproduced
  the reachability with the real `retire` and Minecraft's real destruction queue
  (`retireTwice instanceStillSame=true live=true`).

It also found the tests weaker than their names claim (R5-TEST): the replay test passed with
`recount_marker_sample` stubbed to return `{}`; the crop-origin test checked a list length
rather than a position; `test_the_readback_is_what_carries_the_proof_not_the_screenshots` fails
for "too few near/far pixels" before any screenshot is examined; the fixtures omit
`sampleAtDraw` entirely; and `EvidenceRetentionTest` sat **after** the file's
`if __name__ == "__main__"` guard, so running the file directly never defined it.

And it refused the document's historical figures (R5-DOC) — correctly. Nothing can be
re-measured from an evidence directory that is gone. See
[A note on how runs are cited](#a-note-on-how-runs-are-cited), which now says so plainly, and
the two paragraphs corrected in place (the marker is six quads with a 36-byte push payload, not
one quad with none; and the nether frame's *cause* was never established).

Flags-unset safety was confirmed for a third time, route by route, for both backends.

## Round-5 review repairs (2026-10-06)

Offered to round 6, not treated as acceptance. Each repair is the reviewer's own counterexample
turned into a check and, where it could be, into a test.

**The recount no longer shrinks its own denominator** (B1). A region that would have to be
clamped to fit the retained crop now fails outright — a crop that does not contain the whole
box cannot say anything about the part it omits — and the report's `boxArea`/`controlArea` must
agree with the areas the published geometry resolves to, so a shrunken crop is detectable from
the numbers as well as the geometry. The recount also uses the implementation's own colour
predicate (`>= 200` / `<= 60`) rather than a ±60 neighbourhood, which differed in the 195–199
band.

**`--replay-evidence` runs the marker gate** (B1). The report-only acceptance checks are now one
function, `marker_report_checks`, called by both the stage gate and the replay, so the nine
mutations round 5 replayed as 0 — `attempted=false`, `timesWithAProblem=3`, `enabled=false`,
`drawsRecorded=0`, `depthAttached=false`, `closeFailures=99` among them — all fail. A directory
without `MANIFEST.json` fails rather than passing vacuously. The output lists three things it
does *not* replay, including that it replays recorded evidence and re-runs nothing.

**Retention requires the sample the report points at** (B1), not merely that some sample exists,
and for the terrain probe its reference image too.

**Proofs must not contradict or hollow themselves out** (B3). Each injected feature needs
exactly one note of the form `<name>: offset N verified by read-back`, covering `added`
one-to-one — an empty list and `"FAILED: not verified by read-back"` both pass a substring test
and both now fail. The int64 sentinel must be the value the source actually writes, so
`expected=""` equalling `readBack=""` no longer counts as a measurement. Adoption's `attempted`
is read. `firstMismatch` must be present. Null device handles are rejected. The marker's and
probe's own failure flags are checked here too, since the replay path calls this helper alone.

**Failures reach disk even without a live instance** (B4). Every failure path goes through one
`publishFailure()`, which takes the device and format from the last frame rather than from
`instance`, so a callback arriving after shutdown can no longer leave the previous clean
evidence in place. A raw sample that cannot be written turns an otherwise-clean readback into a
failure instead of being noted and forgotten. Close failures — in the callback *and* on the
unregistered-buffer path — count as problems, so they participate in abandonment.

**Captures are bound to their request** (B4). The draw counter is captured when the copy is
registered, not read from a global when the callback runs, and the gate requires `sampleAtDraw`
to be present, positive, and no greater than the recorded draw count.

**Depth failure is judged orientation-independently** (B4). Round 5 fed the classifier a correct
pattern in one orientation and a wholly yellow box in the other, and it answered "no problem".
The first repair — *exactly one* orientation may satisfy the pattern — **failed its own new
test**: with the good pattern upright and the yellow box mirrored, only one orientation passes,
so the rule was satisfied while depth was broken. The rule that holds is orientation-free: the
rejected colour must be absent from the depth-tested box in **both** orientations, because the
code cannot know which one is real. Two valid patterns are now reported as ambiguous rather than
successful.

**Leaks are counted and bounded** (R4-L1). A retirement that cannot be handed to Minecraft still
leaks deliberately, but the count is published and a budget stops the probe building another
pipeline, so repeated format or device changes cannot pile up silently.

**The tests were softer than their names** (R5-TEST), which round 5 demonstrated and which was
the most useful part of the report:

- `autoAgree` rewrote the aggregates from the sample *after* a test had set them, so several
  readback tests passed for no reason at all. An override that states a count now suppresses it.
- the replay test passed with `recount_marker_sample` stubbed to return `{}`; it now asserts the
  recount's actual numbers against the sample on disk.
- the crop-origin test accepted a hardcoded `[0, 0]`; it now decodes every crop and requires the
  pixels at the stated origin to be the parent frame's pixels there.
- `test_the_readback_is_what_carries_the_proof_not_the_screenshots` built its sample from the
  same empty frames it was testing, so it failed on "too few near/far pixels" before any
  screenshot was examined — it was passing a broken screenshot gate. The sample now comes from a
  good frame while every captured frame is empty, which is what the name claims.
- `McNativeMarkerOrientationTest` is a new Java regression for the orientation attack that needs
  no Minecraft (`McNativeMarkerDraw.select` is the seam). It is what caught the insufficiency of
  the first orientation repair.
- the `__main__` guard sat mid-file, so `python3 scripts/tests/test_marker_gate.py` exited
  before defining `EvidenceRetentionTest`. Discovery found those cases; running the file did not.

Measured: 322 JUnit tests, 86 Python gate tests, and the native stage green as
[20261006T083136-838975Z](runs/native-evidence/20261006T083136-838975Z/MANIFEST.json) — 88
retained files, readback near 12420/12420, far 8316/8316, cell 4224/4224, rejected-in-box 0 in
both orientations, 15 clean readbacks, no unclosed buffers, no leaked pipelines, the independent
recount agreeing with every figure, and zero validation diagnostics. `--replay-evidence` returns
0 and now lists five things it replayed.

## Voxy's real terrain pipeline in Minecraft's frame, measured (2026-10-06)

⚠ **This is an experiment, under round 4's explicit permission** — *"Terrain investigation can
be experimental; it should not be promoted as continuation from an independently accepted
diagnostic layer"* — and round 5 did not change that. Nothing below is offered as an accepted
foundation. It is off unless `-Dvoxy.native.terrain=true`.

The obstacle was structural rather than graphical. `VkTerrainRenderer.record` owns
`beginRendering`/`endRendering`, so there was no way to put Voxy's terrain into a pass someone
else opened. The renderer is now split at that seam:

- `recordBeforeRenderPass(cmd)` — the atlas upload, the depth-bound clear and the host-write
  barrier. These are transfers and layout transitions, so they **cannot** be recorded inside
  dynamic rendering.
- `recordDrawsInRenderPass(cmd, drawCount)` — the pipeline bind, descriptor bind, index buffer
  and indirect draws, and **its own viewport and scissor**: leaving those to the caller would
  let someone else's extent shift the `depthTex` `texelFetch`, which goes wrong by looking
  slightly wrong and says nothing.
- `assertColourFormatMatches(format)` — the format check `record` used to do from the target,
  which this path cannot do for itself.

`VkTerrainRenderTest.theSplitRecordingPathDrawsTheSameImage` renders the same scene both ways
on a real device and requires the images to be **identical** (`compareColor == 0`); splitting a
working path is exactly the kind of change that looks fine and is not.

`McNativeTerrainProbe` then uses that seam. It builds `SyntheticTerrain.boundaryCases()` and
Voxy's real `VkTerrainResources`/`VkTerrainRenderer` **on Minecraft's adopted device**, renders
the scene once into Voxy's own `VkRenderTarget` behind a fence — which both produces the
reference image and performs the out-of-pass work — and from then on records only
`recordDrawsInRenderPass` into a pass opened through **Minecraft's own**
`CommandEncoder.createRenderPass` over Minecraft's colour and depth views. It clears both, so
the frame becomes the synthetic scene; that is what makes a pixel comparison meaningful, and it
is why this must stay a flag.

What is measured: the colour image Minecraft owns, read back right after the draw, against the
reference drawn by the same renderer on the same device. Orientation is not assumed (both are
tried); alpha is excluded because it encodes face/LoD. The claim is **RGB-identical**, and the
gate repeats the comparison from two retained raw samples rather than trusting the reported
aggregate.

Measured, in the run retained as
[20261006T080931-279390Z](runs/native-evidence/20261006T080931-279390Z/MANIFEST.json): Voxy's
real terrain pipeline recorded into Minecraft's own pass produced an image with **26116
non-background pixels and 0 mismatches** against the reference — equal on both sides — over
**16 clean comparisons** across the eleven-checkpoint scenario, with no leaked probes, no
unclosed readback buffers, and zero validation diagnostics. The gate's own recomparison of
`native-terrain-sample-3377.ppm.gz` against `native-terrain-reference-3377.ppm.gz` agrees:
1920x1080, 0 mismatches, 26116 each side.

⚠ This directory, too, no longer replays under the round-5 gate (same missing counter). The
terrain figures above are from its retained files, which are still there to read; the citable
*replay* is [20261006T083136-838975Z](runs/native-evidence/20261006T083136-838975Z/MANIFEST.json),
which carries the same terrain result under the stricter gate.

What this does **not** show: anything about real world data (the input is synthetic — no world
load, no mesh generation, no atlas), anything about coexisting with Minecraft's own scene (the
probe clears it), and anything about performance, LoD selection or the indirect/compute path
feeding real draw counts. `voxy_integration_status` stays `BLOCKED_UNIMPLEMENTED`.

One defect worth recording, because the gate caught it rather than a reviewer: the first version
retired the probe's resources through `VkFrameTracker.freeAtFrameEnd`. The tracker is not the
lifetime that matters — **Minecraft's** submissions hold these resources — and in the measured
run it had already been shut down by the time the window resized, so retirement threw
`VkFrameTracker not initialised` and the stage failed. Retirement now goes to Minecraft's own
`queueForDestroy` with the probe as the `Destroyable`, clears the static reference **before**
queueing (so R5-LIFETIME cannot recur here), refuses to destroy across a device change, and
counts every deliberate leak against a budget that stops it building another.

## Round-6 review repairs (2026-10-06)

Round 6 ([native-integration-review-r6.md](runs/native-integration-review-r6.md)) returned
REDESIGN. It **closed R5-LIFETIME**, left B1/B3/B4/R4-L1 open on narrower residuals, and added
three blocking findings against the terrain experiment. Its judgment of that experiment is the
split this document should keep:

> `CONFIRMED_BOUNDED_MEASUREMENT; REFUTED_GATE_AND_LIFETIME_SOUNDNESS`

— the pixel result stands ("same-device draw into Minecraft's image: CONFIRMED from inspected
source plus retained nonblank pair"), while the gate guarding it and the probe's lifetime
handling did not. Offered to round 7, not treated as acceptance.

**The terrain gate was the marker replay hole, reintroduced** (R6-TERRAIN-GATE). This is the
one worth recording as a process failure rather than a bug: round 5 found that
`--replay-evidence` skipped the marker's acceptance checks, the fix was to extract
`marker_report_checks` so the stage gate and the replay share one function — and the terrain
gate, written immediately afterwards, was not given the same treatment. Its report checks were
replay-invisible in exactly the same way: a terrain report with enabled/attempted/built false,
draws 0, clean 0, problems 3, closeFailures 99 and leakedProbes 3 still replayed as 0. Along
with that: `device` only had to be a *string*, so `"0xdead"` and `"0x0"` passed and were never
compared with the run's other identities; the capture number was checked against the file name
but not for positivity or against the recorded total, so one draw with a comparison at draw
3598 passed; omitting `comparison.flipped` passed. All of it now goes through one
`terrain_report_checks` that both callers use, the device must be a real non-zero handle equal
to the adopted one, and referenced samples must be manifest **members** — manifest success is
not provenance.

**Ownership was compared against itself** (R6-TERRAIN-DEVICE). `ownedByCurrentDevice()` tested
`VkContext.get().device == this.ownerDevice`, and since adoption happens once those are always
the same value — so the check was true even after Minecraft replaced its device. The consequence
was concrete: a rebuild would use context A's resources while labelling the probe with wrapper
B, then record into **B's command buffer with A's resources**. The basis is now Minecraft's
*current* `VkDevice` (`McNativeVulkan.vkDeviceHandle`), divergence stops the probe for the
session and leaks the stale one deliberately with a count, and the gate fails a run that spent
part of itself refusing to record.

**Resources were freed without observing completion** (R6-TERRAIN-WAIT). `build()`'s `finally`
freed the reference target, renderer and resources unconditionally, so a submitted frame whose
fence wait threw reached destruction with completion never observed — the same class of defect
as round 1's B5, in new code. "Submitted" and "wait observed" are now separate facts; without
the second, nothing is freed and the leak is counted. The reviewer also caught a comment
asserting that shutdown avoids `vkDeviceWaitIdle` when `VkFrameTracker.destroy` does call it.

**The geometry is asserted, not accepted** (B1). The gate required the retained crop to
*contain* the published geometry — but the producer publishes that geometry too, so moving both
together made the same partial crop pass. Any check against producer-supplied reference data
has that weakness. The geometry is a compile-time constant, so `EXPECTED_MARKER_GEOMETRY` now
asserts the constant and rejects regions the gate does not know. An empty manifest, which
previously produced no mismatches and passed, now fails.

**Distinct features must have distinct offsets** (B3). Four features could all claim offset 0
and pass, which means at least three were never located; the read-back experiment resolves one
offset per feature, so duplicates now fail. Duplicate entries in `added` also fail, since the
set comparison accepted them.

**The rejected orientation is now evidence** (B4). Only the selected orientation's pixels were
retained, so "exactly one orientation matches" was the implementation's word. Both crops are
retained — the rejected one gzipped, with its own rect — and `recount_rejected_orientation`
requires the pattern to be *absent* there and the box to be free of the rejected colour in both.

⚠ **The honest limit**: a single y-ambiguous image cannot establish *which* orientation a draw
produced. The strongest claim available is "exactly one orientation matches the expectation, and
the box is clean in both", now checkable from retained pixels. That is what is claimed.

**Both uncounted leak paths count** (R4-L1): `shutdown()` when Minecraft's device is
unreachable, and `shutdownImmediate` when the waited device is not the owner.

Measured: 326 JUnit tests, 107 Python gate tests, and the native stage green as
[20261006T095436-054038Z](runs/native-evidence/20261006T095436-054038Z/MANIFEST.json) — 121
retained files, 2.6 MB, every one of the five gates (environment, marker, proofs, terrain,
depth) passing. The marker readback recounts to near 12420/12420, far 8316/8316, cell
4224/4224, rejected-in-box 0 in both orientations; **the rejected orientation's retained crop
contains none of the pattern** (`near 0/12420, far 0/8316, cell 0/4032, satisfiedRegions 0`),
which is the "exactly one orientation matches" claim checked from pixels rather than asserted.
The terrain comparison is still 26116 non-background pixels each side with 0 mismatches.
`--replay-evidence` returns 0 and now lists six replayed checks, the terrain acceptance checks
among them.

## Minecraft's scene depth is not observable through a buffer copy (2026-10-06)

This was measured because depth coexistence — Voxy's LoD drawing behind Minecraft's own terrain
— needs Minecraft's scene depth, and two things had to be known first: whether that image can
be read back at all, and which way its Z runs. Decompiling showed `VulkanCommandEncoder` derives
the copy aspect from the format via `VulkanConst.formatAspectMask`, so depth *should* be
copyable — which is not a measurement.

`McNativeDepthProbe` (flag `voxy.native.depth`, read-only — it writes nothing to Minecraft's
images) reads the depth attachment of `mainRenderTarget()` at the tail of `LevelRenderer.render`.

Measured: the copy **completes**, and all **1,639,680 pixels of a 1708x960 D32_SFLOAT image are
0.0**, recounted by the gate from the retained raw depth
(`native-depth-sample.pgm.gz`, quantised to 16 bits) rather than taken from the probe's summary.

⚠ **The isolation claim is weaker than it first read.** The all-zero value is also Voxy's
`VkDepth.CLEAR` (reverse-Z far), so the first run — which had the terrain probe clearing depth —
was suspect. A run with the depth flag alone gave the same result, but **that run's evidence was
never committed**, so a reader cannot check it from this repository; round 7 recorded it as
`isolated_run_independently_confirmed: false` and was right to. What is retained and checkable is
the all-zero depth of the normal native stage. The isolated observation happened and is reported
here as an observation, not as retained proof. (Round 18 refined "all-zero": the newest run's
16-bit copy holds 55 841 code-1 samples among 1 639 680 — near-zero and in the lowest histogram
bin, not uniformly zero; raw floats are not retained, so this says no more about the cause.)

**A completed transfer is not an observation.** The conclusion is negative and bounded: this
path does not observe Minecraft's scene depth. The Z convention is **unmeasured**, and the probe
no longer has an opinion about it.

⚠ Round 7 (R7-DEPTH-GATE) refuted the first version of this section's apparatus on two counts,
both correct. The probe inferred reverse-Z from "the bottom band is nearby ground, the top band
is sky", and the gate **rejected "unknown" whenever the bands separated** — forcing certainty
out of an unproven premise, since a wall, a cave, a tilted camera or a flipped transfer each
reverses it, and the positive fixtures encoded the premise rather than measuring it. The
inference is **deleted**: the probe publishes `zConventionMeasuredHere: false` and no convention
at all, and the gate fails any report that carries one. Separately the gate never remeasured
anything, so contradictory summaries passed — with the real all-zero histogram intact,
`min=0 / max=1 / bottomMean=0.8 / reversedZ=true` was accepted as "readable; reverse-Z". The raw
depth is now retained and recounted, with the histogram, extrema and band means required to be
what the pixels say. Why the
image reads as uniform is **not established**: the depth view reachable at that hook may not be
the one the scene was drawn into, or the copy may not transfer depth contents despite
completing. Neither is claimed.

What this changes about the next step: depth coexistence should be approached **behaviourally**
rather than by readback — draws at known depths against a `LOAD`ed depth attachment, which is
the technique `McNativeMarkerDraw` already demonstrates works on Minecraft's own depth image.
That infers the convention from what survives the depth test instead of from a transfer.

## Minecraft's depth at the level-render tail, tested behaviourally (2026-10-07)

The buffer copy gave 0.0 for every pixel, and round 7 was right that a completed copy is not
an observation. So the question was put to Minecraft's **own depth test** instead.

`McNativeDepthLadder` (flag `voxy.native.depthladder`, default off) opens Minecraft's pass with
colour **and depth `LOAD`ed**, and draws eight narrow columns across one screen band at known
NDC depths `z_i = (i + 0.5) / 8`, **compare `LESS`, `depthWriteEnable = false`**. Column `i`
survives exactly when `z_i < d_mc`. Above every column it draws a co-located `ALWAYS` stripe, and
below the band a separate `ALWAYS` band. Nothing is written to Minecraft's depth and nothing is
cleared; the colour writes are the measurement. The co-located stripe is what gives an empty
rung its meaning: if the stripe above column `i` is filled and the rung is not, the only
difference between the two draws is the depth comparison, so "nothing was drawn there" and
"something covered it" are both excluded.

**Isolation is retained this time, not asserted.** The terrain probe clears the very attachment
the ladder measures, and the marker writes its own depth, so the ladder gets its **own Minecraft
launch** inside `--only native`, with those two off. The ladder's report publishes
`terrainProbeEnabled`, `terrainDrawsRecorded`, `markerDrawEnabled` and `markerDrawsRecorded`
(the flag *and* the count, since a flag alone cannot tell "enabled but drew nothing" from
"off"), and the gate refuses a run where any of them is set. That launch's evidence is retained
under `ladder/` in the run directory — report, raw crop, checkpoints and log, all manifest
members — and replays with the same gate the stage used. Round 7 refused the depth probe's
isolation claim because the isolated run had never been retained; that is the defect this
closes.

Measured, in the retained run tabled above:

```
inline ALWAYS control (per column)   ~0.93 filled, all eight columns
separate ALWAYS control band          ~0.98 filled
LESS rungs z = 0.0625 .. 0.9375       all empty, fill 0.0
=> Minecraft's depth in that band is <= 0.0625
```

recounted by the gate from the retained crop in the orientation the report publishes — the
earlier scratchpad runs of the same probe gave the same numbers (0.926–0.933, 0.978, all rungs
empty) and are cited here only as narrative, since they were not retained.

**What this establishes — less than this section first said.** At the `LevelRenderer.render`
tail, the depth attachment Voxy can reach holds a value ≤ 0.0625 across the tested band, by
Minecraft's own depth test with no transfer involved. That is all.

⚠ **Retracted (2026-10-07, same day, before round 8 reported):** the first version of this
section concluded that "a depth test at this hook cannot compose Voxy's terrain against
Minecraft's scene" and that coexistence needs an earlier hook. That conclusion is **withdrawn**.
Two facts undo it (the first from the run's screenshots, which are not retained; the second
from the 26.2 bytecode, which anyone can read):

1. The band is not sky. In the run's screenshots (not retained — the next revision retains a
   crop of the band) the region between the two cyan stripes is forest terrain tens of blocks
   from a camera at y=120. So the ladder was testing against real scene geometry.
2. Minecraft's frame graph clears the main target's depth to **0.0** — `LevelRenderer.render`'s
   first pass calls `CommandEncoder.clearColorAndDepthTextures(colour, …, depth, 0.0)` (26.2
   bytecode, `dconst_0`). 0.0 is the reverse-Z *far* value, and under a reverse-Z perspective
   projection the depth of a surface at distance *d* is of order near/*d*: with a near plane of
   0.05, everything beyond about one block has depth below 0.0625. The ladder's rungs were
   linear in [0.0625, 0.9375], i.e. entirely inside the first block in front of the camera.

So "every rung rejected over terrain" is **exactly what a correct reverse-Z scene depth looks
like** to this ladder, and also what a cleared attachment looks like. The measurement does not
distinguish them. The buffer copy's *exact* 0.0 for every pixel still stands as a separate
observation and now pulls the other way — a correct reverse-Z scene would show small non-zero
values there — so the two results disagree about what the attachment holds, and neither is
promoted. The clear-to-0.0 is source evidence about Minecraft's clear value, **not** a
measurement of which direction is nearer; the convention remains unmeasured.

**What decides it:** the same ladder with rungs placed where reverse-Z depth actually lives,
2⁻¹⁶ … 2⁻² (about 3000 blocks down to 0.2 blocks at near 0.05), over the same terrain band, in
the same isolated launch. A surviving prefix that ends somewhere in the middle bounds a real
scene depth there and leaves the copy's 0.0 unexplained; no survivor down to 2⁻¹⁶ says the
attachment reads below that at this hook. (Written before round 8; the prefix rule and the
per-column reading it assumes were refuted there — see the sections that follow.)

**Measured with the re-rung ladder (2026-10-07, run
[20261007T011026-211934Z](runs/native-evidence/20261007T011026-211934Z/MANIFEST.json), its own
isolated launch, retained under `ladder/`):** with rungs 2⁻¹⁶ … 2⁻², column 0 (z = 2⁻¹⁶) is
**76 % magenta**, column 1 (2⁻¹⁴) 7 %, column 2 (2⁻¹²) 0.1 %, the rest 0; every control filled
as before. So **at the pixels where column 0's rung passed, the attachment holds depth above
2⁻¹⁶** — non-zero. The retained crop (`ladder/native-depth-ladder-2.ppm.gz`) also shows what
the band was looking at when the sample was taken — draw 2, the first frames after the world
opened: clouds over sky in this band, and the magenta lies over the cloud shapes. ⚠ Round 9
held the first version of this paragraph to the round-8 standard, correctly: columns 0 and 1 are
different pixel sets, so "7 % of column 1" does not bracket any cloud pixel from above, and the
clouds' distance cannot be put in a two-sided range from this ladder (the per-pixel ladder
below can). Nothing is claimed about the rest of the frame or the world. The observation is
nonetheless **inconsistent with the buffer copy's exact 0.0 at every pixel** of a frame taken
at the same hook in a different launch; the copy is the suspect, its failure mode is not
identified, and the two observations are not the same frame. The first run's all-rungs-rejected
result is consistent with this: its rungs started at 0.0625.

⚠ Round 8 ([r8](runs/native-integration-review-r8.md)) refuted this ladder's *inference*, not
its colour counts: eight columns test eight different pixel sets, so a prefix of survivors is
not a bound on one depth; the adjacent ALWAYS stripe is not a same-pixel control and there is
no positive control for the LESS path; "< 80 % filled" is not "rejected at every pixel"; NaN
and a producer-controlled target extent pass the gate. The next revision stacks every threshold
on the **same pixels** and encodes each pixel's bracket in colour, adds a GREATER draw as the
positive control, and samples repeatedly through the session rather than once at draw 2.

**What this does not establish.** The Z convention: a single band of known depths bounds a
*value*, and which direction of that value is nearer cannot be read from one band — the same
hole round 7 found in the band heuristic, which is not reintroduced; the report asserts
`zConventionMeasuredHere: false` and the gate fails any other value. Minecraft's clear-to-0.0
and swapped near/far are source facts, not this probe's measurement. The band is one region of
the screen; nothing is claimed about other regions, about whether terrain was loaded anywhere
in that frame or world (the retained crop shows clouds over sky in this band and nothing else),
or about the copy's failure mode.

**The gate.** `ladder_report_checks` is one function called by the stage and by
`--replay-evidence`; the rung depths and the three band rectangles are asserted against the
source constants, not read from the report; the surviving set must be a prefix (compare `LESS`
on a monotonic ladder cannot pass a deeper rung after failing a shallower one) and the published
bounds must be the ones that set implies; the sample is bound to its capture by name and count;
the orientation is published and the recount resolves every band through the implementation's
own formula in that orientation, refusing a crop that does not contain a band rather than
clamping it; the report's fills must be what the pixels say; the device must be the one the
ladder launch's own eleven checkpoints saw (a second process has a second `VkDevice`, so the
first launch's identity is the wrong reference). 41 Python cases cover the counterexamples —
the inline control below the floor in the report and in the pixels, survivors that are not a
prefix, bounds the survivors do not justify, a sample named for another draw, a lie about the
orientation, a 1x1 image, a shrunken crop, a terrain- or marker-contaminated run, published
bands that drift from the source, a ladder whose launch saw another device — and three JUnit
cases pin inertness without the flag and the published constants.

## Round-8 review repairs, and the per-pixel ladder (2026-10-07)

Round 8 ([native-integration-review-r8.md](runs/native-integration-review-r8.md)) returned
REDESIGN against `3421640f`. It **closed B3, B4, R6-TERRAIN-DEVICE and R7-DEPTH-GATE**, left
B1 and R6-TERRAIN-GATE open on narrower residuals, and added four blocking findings against the
ladder. Its split is the one to keep:

> `REFUTED general mechanism inference, gate, bound and hook conclusion; CONFIRMED draw state,
> retained isolation and actual colour counts`

**The ladder measured eight pixel sets, not one depth** (R8-LADDER-MECHANISM, R8-LADDER-BOUND).
Each column tested a different threshold at different pixels, so "survivors form a prefix" was
not a physical necessity and "≤ z₀ across the band" was never implied — a valid depth field
`d_i = z_i / 2` fails every rung and most columns exceed z₀. The adjacent `ALWAYS` stripe
proved nearby colour work reached the copy, not that the `LESS` path worked at the rung's
pixels; there was no positive control for the depth path at all. And "< 80 % filled" was read
as "rejected everywhere", when half a column can visibly pass above the claimed bound.

**The ladder is now per pixel.** One band; an `ALWAYS` base (white); a `GREATER` control at z₀
(grey) — the complementary control: where every `LESS` rung fails, `GREATER` passes unless
the depth is *exactly* z₀, so a pixel left white is either that equality or a depth test that
decided nothing — in both cases the sample is refused, not interpreted. (Round 9: this is a
same-pixel complement, not a calibration of an arbitrarily broken `LESS` path — a `LESS`
pipeline that behaved as `ALWAYS` would paint everything violet and pass as "d > z₇"; the
retained crops are spatially structured and the source state is inspected, but the gate alone
cannot exclude that.) Then the eight `LESS` rungs ascending, each in
its own colour, so a pixel's final colour is `max{i : z_i < d}`. Every pixel of the band ends up
in one of ten palette colours, and the gate counts them: `other` (outside the palette) must be
zero — the band was drawn and the orientation is right — and `anomaly` (still white) must be
zero. The published counts must equal the recount from the retained crop exactly; the rejected
orientation's crop is retained too and must be mostly outside the palette; and since round 9 a
quarter-scale thumbnail of the **whole readback** is retained per sample and both crops must
average to it at their stated positions, so a crop's position and orientation are anchored to
independently retained pixels rather than asserted. No prefix rule, no 80 % threshold, no
band-wide bound: the output is a
histogram of per-pixel brackets, per sample. Samples are taken from draw 2 and every 240 draws
after it (up to 24), each retained, so the measurement is no longer one frame from before the
world loaded; a crop of the band from every captured screenshot is retained beside them.

**Gate residuals closed with it.** NaN is rejected in every number the ladder and depth-copy
gates read (R8-LADDER-GATE; round 9 found the depth copy's means still accepted it, fixed
with the rest of the round-9 repairs below); the sample's frame extent must be one the ladder launch's own lifecycle
checkpoints observed, so a self-consistent tiny crop cannot stand in for the real frame; the
launch's own `native-result.json` must equal the summary's copy of its checkpoints; `reversedZ`,
`lowerBound` and `upperBound` are rejected as keys. B1: every file in the evidence directory
must be a manifest member — reports, summary, source fingerprint, logs — not only the samples;
the marker's selected and rejected crops must be named for the capture the readback states.
R6-TERRAIN-GATE: the adopted identity is parsed one way (hex or decimal) and a missing or
unparseable one fails instead of disabling the comparison. The depth copy's device must be the
run's checkpoint device; `clearedValue`/`clearedShare` are recounted; replay refuses to skip a
depth or ladder gate that the retained summary says ran. The JUnit test that round 8 showed
passing against `depthWriteEnable(true)` now inspects the depth-stencil state the pipelines
are built from, for all three compare ops, and the depth-probe test's vacuous assertion is
gone.

Measured, run [20261007T020025-032770Z](runs/native-evidence/20261007T020025-032770Z/MANIFEST.json):
18 samples (draw 2, then every 240 draws to 4082), **every one with `other` = 0 and `anomaly` = 0** — the band was drawn at every pixel and the depth test decided every pixel, in every sample. Draw 2 (world just opened): 42 825 px below 2⁻¹⁶ (sky), 28 131 px in (2⁻¹⁶, 2⁻¹⁴], 7 892 px in (2⁻¹⁴, 2⁻¹²] — the clouds. Draws 242–962 and 1682–2882 (overworld, spawn camera): 11–25 k px below 2⁻¹⁶ and 53–67 k px in (2⁻¹², 2⁻¹⁰], and the retained crop reads as a depth silhouette of the hills against the sky. Draws 1202 and 1442 (just after the travel teleport): every pixel below 2⁻¹⁶ — consistent with the cleared value, which is one such value, not shown to be it. From draw 3122 (after the resize, band 1152×86) the same terrain bracket; draw 3602 (nether): 80 030 px below 2⁻¹⁶, 2 738 px in (2⁻¹⁰, 2⁻⁸] and 16 304 px in (2⁻⁸, 2⁻⁶] — near walls. If the source facts about the projection hold (near 0.05, reverse-Z — direction still unmeasured), (2⁻¹², 2⁻¹⁰] is roughly 50–200 blocks and (2⁻⁸, 2⁻⁶] 3–12 blocks. Replay returns 0 with nine checks; 36 crops (both orientations of 18 samples) and 22 band crops from the screenshots are retained, 7.9 MB.

**Round-9 review repairs (2026-10-07).** Round 9
([native-integration-review-r9.md](runs/native-integration-review-r9.md)) returned REDESIGN
against `c05e0a94`: it closed R6-TERRAIN-GATE, R8-LADDER-MECHANISM, R8-LADDER-BOUND and
R8-LADDER-CONCLUSION, confirmed the per-pixel counts of all 18 samples by independent exact-RGB
recount, and refuted the gate on four concrete attacks, all repaired in the commit after it:
(1) flipping both orientation booleans *and* recomputing both rectangles, with the same crop
bytes, passed — the recount knew the crop, not where it came from; now a quarter-scale
thumbnail of the whole readback is retained per sample and both crops must average to it at
their stated rectangles. (2) Dropping 17 of 18 samples from the report, with their crops still
retained, passed; now every retained ladder crop must belong to a listed sample and the set of
sampled draws must equal the ones the launch's log records. (3) A nested `ladder/MANIFEST.json`
was exempt by basename; only the root manifest is. (4) Deleting the source fingerprint or a log
together with its manifest entry passed; replay now requires a fixed inventory (summary,
fingerprint, logs, every proof file, and the ladder/terrain/depth files the summary says ran).
Also: NaN in the depth copy's means (R9-DEPTH-FINITE); the JUnit test pins the compare-op
table that `create()` and `record()` index (round 10 showed that a substitution at the creation
call itself still escaped that test; see the round-10 paragraph); the Python tests pin the depth/band/palette literals rather than
reading them back from the gate; and the overstatements round 9 named in this document are
corrected in place above (the "cleared value" attribution, the clouds paragraph's cross-column
range, "both checkable", the unconditional positive-control wording).

**Round-10 review repairs (2026-10-07).** Round 10
([native-integration-review-r10.md](runs/native-integration-review-r10.md)) returned REDESIGN
against `6b2d6cc9`: it closed R8-LADDER-GATE's two attacks and R9-DEPTH-FINITE, confirmed all
18 per-pixel counts and every anchored block, and left three blocking items, repaired in the
commit after it: (1) B1 — `source-sha256.json` only had to exist and be hash-listed; replaced
by `{}` it still replayed. Replay now parses it, requires hundreds of entries including the
runner, the build file and the ladder/marker sources, and **requires every listed file to hash
to the same bytes in the checkout the replay runs in**. (Round 11: that still bound only the
*listed* files — a partial inventory passed; the key set must now equal the checkout's own
source inventory, see the round-11 paragraph.) (2) R10-CREATE-TEST — pinning the table was not enough: a
substitution at `create()`'s own call site escaped. Pipeline creation now goes through
`buildPipelines(..., creator)`, and the JUnit test calls that same method with an injected
creator that inspects the create-infos it is handed: three pipelines, `LESS`/`ALWAYS`/`GREATER`
at the table's indices, depth test on, depth writes off, in the structs the real path builds.
(3) R9-SURVEY-OVERCLAIM — "none was loaded in the sampled frame" is withdrawn above; the
crop shows clouds over sky in one band and says nothing about the rest. Non-blocking items:
the log's orientation and eleven counts per sample are now reconciled with the report
(R10-LOG-DETAILS); the depth fixture is 100 rows so its band mean covers two rows
(R10-DEPTH-FIXTURE). **Stated limits that remain** (R10-ANCHOR-GRAIN, R10-CONSISTENT-FORGERY):
the thumbnail anchors complete 4×4 blocks — about 1300–2300 boundary pixels per crop are not
compared, and a within-block permutation is invisible to it; and every gate here is an
internal-consistency check over editable records — a jointly rewritten report, crops,
thumbnail, log and manifest can pass, as no hash rooted in an editable manifest can prove GPU
origin. The replay's claim is "the checks listed in its `replayed` output hold over these
records, against the pinned source constants and this checkout's sources", not authenticity
against a forger, and not agreement of every retained field with every other (round 13 found
the direction labels unreconciled; see its paragraph).

**Round-12 review (2026-10-07).** Round 12
([native-integration-review-r12.md](runs/native-integration-review-r12.md)) returned REDESIGN
against `9b6a1816` with **both round-11 findings closed** (B1, R10-CREATE-TEST): the only
blocking item is the standing delivery boundary — bounded diagnostic observations are not an
accepted foundation and do not deliver native Voxy LoD — which this document has never
contested. It confirmed 19 samples, 198 546 anchored blocks and the 408-entry inventory match,
and named as non-blocking limits (stated here, not repaired): a creator that rewrites the state
for the Vulkan call and restores it afterwards passes both JUnit and the gate (the Vulkan call
is outside any test and is not claimed to be checked by anything); block-grain anchoring; consistent
forgery. Two wordings it called too strong are softened in place ("were created with" in the
gate message; "checked by the measurement" above now reads as a limit, not a check). For the
first time in twelve rounds no evidence or gate finding is open; the next work is the goal
work, starting with the Z direction below.

**Round-11 review repairs (2026-10-07).** Round 11
([native-integration-review-r11.md](runs/native-integration-review-r11.md)) returned REDESIGN
against `867cd25d`: it closed R9-SURVEY-OVERCLAIM, R10-LOG-DETAILS and R10-DEPTH-FIXTURE,
confirmed all 18 counts and 186 450 anchored blocks, and left two blocking residuals, repaired
in the commit after it. (1) B1 — the fingerprint comparison bound only the files the fingerprint
*listed*: four required names plus a hundred `docs/` files, the real list minus one source, or a
hundred `./` aliases of one file all passed. `source_binding` now requires the key set to equal
the checkout's own source inventory (the same `src/`, `scripts/`, `build.gradle`,
`gradle.properties` selection the launch fingerprints), rejects non-normalized names, and
compares every byte. What that binds is the selected sources of this checkout to the run — not
commit identity, dependency jars, Minecraft/Sodium binaries, the JVM, the driver or the GPU
execution, which the reviewer correctly lists as outside it. (2) R10-CREATE-TEST — the test's
injected creator replaces the production one, so a mutation *inside* the production creator
(rewriting the already-built state before `vkCreateGraphicsPipelines`) escaped. `buildPipelines`
now reads the depth state back from the create-info **after the creator returns** and refuses
the pipelines if it is not the table's op with the test on and writes off; the observed states
are published as `pipelineStates` and `depthWritesEnabled` is derived from them rather than
written as a literal; the gate pins both. A JUnit test injects a creator that rewrites the state
and requires the refusal. The production creator is now a three-line `vulkanCreator` whose only
act is the Vulkan call; a mutation that builds its own struct inside it, or rewrites the state
for the call and restores it, is beyond any test without a device and beyond the post-return
read-back — a stated limit, not something the measurement is claimed to check. Replay table:
after this change **only the newest run replays**, by design; every older cell says "no".

**What the per-pixel ladder does and does not say.** It gives, per sample and per pixel of one
band, the bracket of rungs the depth attachment's value falls in, by Minecraft's own depth
test, with the depth path positively controlled. It does not say which direction is nearer
(a single band without the two-look checkpoints cannot read it, and the probe never claims it;
the gate reads it from the two straight-down looks in the next section), nor why the buffer copy read 0.0 where the test
sees non-zero values, nor anything about other regions of the screen.

## The Z direction, measured (2026-10-09)

Rounds 7–12 kept one thing honestly unmeasured: a band of known depths brackets a *value*,
and which direction of that value is nearer cannot be read from one look. The source facts
(Minecraft clears depth to 0.0; `setPerspective` is called with near and far swapped) pointed
at reverse-Z but were not a measurement. The measurement needs two looks at known geometry.

**Method.** Two lifecycle stages were added to the harness, after `overworld` and before the
disconnect: `descend` teleports the player to **12 blocks above the highest block under
(0, 0)** (the client heightmap, recorded in the checkpoint as `groundY`), pitch 90 — straight
down; `ascend` to **108 blocks above** the same block, pitch 90. The ladder, in its own launch
as before, now labels every sample with the harness stage it was taken in and with the main
camera's position and rotation at the moment the readback was requested. The gate — not the
probe, which still asserts `zConventionMeasuredHere: false` — takes the last sample of each of
the two stages whose camera is where that stage put it (y = ground + offset + the 1.62 eye
height, within one block; pitch 90 within one degree; x and z within two blocks of the
teleport; yaw is not checked), requires no pixel
below the smallest rung in either (straight down at the ground, nothing is sky), and compares
the bracket indices: if every bracket of the nearer look lies strictly above every bracket of
the farther look, the larger depth value is nearer; if strictly below, the smaller is. Anything
else fails the stage. The direction is therefore derived from the retained per-pixel counts of
two retained crops, anchored to retained thumbnails, with the camera positions and the ground
height retained beside them.

**Measured** (run [20261009T033539-494735Z](runs/native-evidence/20261009T033539-494735Z/MANIFEST.json),
24 samples; confirmed by round 13; re-measured identically as [20261009T041151-205062Z](runs/native-evidence/20261009T041151-205062Z/MANIFEST.json), 20 samples, which replayed 0 in the checkout it was built from — see the replay table for what replays now):

| Look | Camera y | Above ground (y = 67) | Brackets with pixels | Pixels |
| --- | --- | --- | --- | --- |
| descend, draw 5042 | 80.62 | 13.6 | 3 = (2⁻¹⁰, 2⁻⁸], 4 = (2⁻⁸, 2⁻⁶] | 45 734 + 53 338 |
| ascend, draw 5522 | 176.62 | 109.6 | 2 = (2⁻¹², 2⁻¹⁰] | 99 072 |

Every pixel of the nearer look holds a **larger** depth value than every pixel of the farther
look: **the larger depth value is nearer — Minecraft's main depth attachment is reverse-Z**,
at this hook, by its own depth test, in two retained looks whose geometry the harness set. The
retained thumbnails show the ground patch in both (grass and trees from 13 blocks; the same
hills as a far carpet from 110). Under near = 0.05 the brackets correspond to roughly 3–51
blocks and 51–205 blocks, consistent with the heights; that correspondence is an illustration,
not part of the measurement.

**What this does and does not say.** It establishes the direction of Minecraft's depth values
in the attachment reachable at the `LevelRenderer.render` tail, for a world the harness built
from a fixed seed, with the camera straight down over one spot. "The same ground" means this
local terrain: the band is off-centre, so the higher camera's footprint is larger and
displaced, and no claim is made that the two looks hit identical surfaces or that every ray's
distance is bounded by the one heightmap column — what is claimed is that every pixel of the
lower look reads nearer than every pixel of the higher look, which the retained thumbnails
(grass and trees from 13 blocks; the hills as a far carpet from 110) make a plain reading of
near and far. It says nothing about the depth *scale* (near/far planes, or whether the mapping
is the swapped-`setPerspective` one the bytecode suggests), about other hooks, or about
coexistence: a depth-tested Voxy draw at this hook is the next experiment, and it will be gated
the same way. The probe still publishes no convention; the direction is the gate's reading of
two looks, and every input to that reading is retained.

**Round-13 review (2026-10-09).** Round 13
([native-integration-review-r13.md](runs/native-integration-review-r13.md)) **confirmed the
direction as a bounded observation** of these two looks (nearer look identified independently
of any depth value) and refuted the gate's *consistency*: it trusted the report's stage labels
and cameras, so swapping them in the report alone — images, log and checkpoints untouched —
reversed the answer; split grounds in the checkpoints did too. Repaired after it: each ladder
log line now carries the sample's stage and camera and the gate reconciles both with the
report; the harness's own `stage=` lines in the log must have the sample's stage current when
it was logged; the two checkpoints must state one ground, and their `cameraY`/`playerY`/
`playerPitch` must be what that ground and the teleport imply; the sample's camera must match
the checkpoint's; the lower camera must be the `descend` one and precede the `ascend` one;
replay requires the summary's saved direction to equal the recomputed one. Tests now cover
the x/z eligibility, the relabelling, the split ground, the order and the saved direction.
Still true, as disclosed: a jointly rewritten package (images, counts, log, summary, manifest)
can pass; these are consistency checks over editable records.

**Round-14 review (2026-10-09).** Round 14
([native-integration-review-r14.md](runs/native-integration-review-r14.md)) **closed
R13-Z-BINDING and R13-TEST-XZ**; the only blocking item is again the standing delivery
boundary. Non-blocking: two of the new reconciliation guards (the sample log line's stage
against the report; the sample's camera against the checkpoint's) had no test that fails
without them — both now have one; and the documentation drift it listed (a sentence still
calling the direction unmeasured, the date, the handoff, two source comments overstating what
the post-return read-back proves) is corrected in place.

## Coexistence, first measurement: a known-depth draw against Minecraft's loaded depth (2026-10-09)

With the direction known, the first coexistence question can be asked per pixel: does a draw
with **Voxy's own depth compare** (`VkDepth.COMPARE_OP`, `GREATER_OR_EQUAL`, reverse-Z) against
Minecraft's **loaded** depth appear exactly where Minecraft's depth says it should?

**Method** (flag `voxy.native.coexist`, default off, enabled only in the ladder launch). On
every frame the ladder samples, after the ladder's readback is requested, a second `LOAD` pass
draws one quad over the same band at **z\* = the depth of rung 4 (2⁻⁸)** with the
`GREATER_OR_EQUAL` pipeline, **no depth write**, in a colour outside the ladder palette; then a
second readback. The first crop gives every pixel's bracket of Minecraft's depth; because z\* is
a rung value, no bracket straddles it: pixels below the smallest rung or in rungs 0–3 have
d ≤ z\* and the quad **must** be there; pixels in rungs 4–7 have d > z\* and it **must not**,
and those pixels must still hold their ladder colour. The gate recounts both retained crops
pixel by pixel, requires zero violations and zero pixels outside both palettes, requires the
published counts and the log's coexist line to equal the recount, and requires at least one
sample with pixels on both sides of z\* (the `descend` look straddles rungs 3 and 4), or the
experiment decided nothing. The quad's pipeline is the fourth entry of the pinned
`pipelineStates`.

**Measured** (run 20261009T045515-905630Z, which replayed 0 in the checkout it was built from; the figures below are that run's, the current run's are in its `ladder/` directory): 24 samples, every one with a coexist result, **zero violations in all 24** — no pixel whose bracket says d ≤ z\* lacked the quad, no pixel whose bracket says d > z\* showed it, and every uncovered pixel kept its ladder colour. Five samples hold pixels on both sides of z\*: draw 2 (clouds, 77 092 shown / 1 756 hidden), the two nether looks (82 768 / 16 304 each) and the two `descend` looks (45 734 shown / 53 338 hidden each) — the per-pixel composition is exact where it matters. The other 19 samples are entirely below z\* and entirely covered, as predicted. Replay returns 0 with ten listed checks (the coexist checks run inside the ladder's); the quad's pipeline state is the fourth pinned entry `[6, 1, 0]`.

**Round-15 review (2026-10-09).** Round 15
([native-integration-review-r15.md](runs/native-integration-review-r15.md)) **confirmed the
coexistence measurement** — same frame, same pixels, Voxy's compare op, no depth write, the
bracket rule sound including d = z\*, zero violations in an independent exact-RGB recount of all
24 pairs (2 114 816 pixels) — and refuted the gate once more, blocking: a report flagged
`coexistEnabled: false` with no results replayed as 0 while the retained launch command, the
log's 24 coexist lines and the saved results said the experiment ran (R15-COEXIST-PRESENCE).
Repaired after it: the stage and replay take whether the launch enabled the experiment from
the retained launch command and require the evidence when it did; a log holding coexist lines
is reconciled even if the report says off; duplicate results are refused. Non-blocking, also
repaired: pixels were compared by three-level class, so a quad pixel of another green or an
uncovered pixel of another shade passed — both sides now compare exact bytes (`(128, 255, 128)`
for the quad, byte-identical to the first crop for uncovered pixels); twelve of the gate's
coexist checks had no test that fails without them — tests were added, but round 16 found five
guards whose removal still passed the suite (see below). Stated limits: the
recorder's pipeline binding is outside JUnit (a wrong binding fails the measurement, not a
unit test); a passing pixel result does not authenticate that the GPU executed the compare, any
more than any other retained record does.

**Round-16 review (2026-10-09).** Round 16
([native-integration-review-r16.md](runs/native-integration-review-r16.md)) **confirmed the
measurement again** (independent exact-RGB recount of all 24 current pairs, 2 114 816 pixels,
zero violations, four mixed samples; the original round-15 presence attack is refused) and
refuted the repair as a whole, blocking: **R16-COEXIST-LAUNCH-SEMANTICS** — `build.gradle`
switches every harness flag on `project.hasProperty`, so `-PharnessNativeCoexist=false` and the
bare `-PharnessNativeCoexist` both launch the experiment, while the stage and replay tested
only the literal `=true` token and accepted an off report behind either command. Non-blocking:
five coexist guards (result-list type, result-set equality when no crop is orphaned, crop and
thumbnail names when the file exists under the wrong name, per-draw log count equality) had no
test that fails without them; documentation still dated the gate 2026-10-07, called the
round-14 and round-15 runs "replays in this checkout", and the handoff omitted coexist from the
second launch's flags. Repaired after it (`9c6cf401`): `launch_enables()` reads the retained
command with Gradle's semantics — any token naming the property enables it, a different
property with the prefix does not — in both the stage and replay, with replay tests for
`=false`, `=`, the bare token and the non-enabling neighbour; each of the five guards has a
test, checked by mutating the guard to `False` in turn; this file and the handoff say "replayed
in the checkout it was built from" where they said "replays here". Round 16 also recorded, as a
mutated-gate observation, that with the per-draw log comparison removed a log line whose count
is off by one replays 0; that guard is now tested.

**What this does and does not say.** A draw with Voxy's compare op against Minecraft's loaded
depth composes per pixel, at this hook, for a quad at one known depth — the first
coexistence measurement, with zero tolerance. It is not Voxy's terrain pipeline (that is the
next section), it writes no depth, and it says nothing about the depth *scale* beyond "a depth
value of 2⁻⁸ in Voxy's convention lands where Minecraft's 2⁻⁸ does" — which is the one scale
fact coexistence needs, and is exactly what this measures.

**Round-17 review (2026-10-09).** Round 17
([native-integration-review-r17.md](runs/native-integration-review-r17.md)) confirmed the
measurement a third time (24 pairs of the new run, exact RGB, zero violations), confirmed that
all 23 coexist refusal guards now have a failing test, and kept R16-COEXIST-LAUNCH-SEMANTICS
open on a residual: Gradle also sets a project property from the separated `-P x`, from
`--project-prop x` / `--project-prop=x` and from the system property `org.gradle.project.x`
(`-D…`, `--system-prop …`), measured against `./gradlew help` for all nine harness switches, and
`launch_enables()` read none of those, so three such commands replayed 0 behind an off report.
Repaired after it: `launch_enables()` reads every one of those forms; and, because modelling
Gradle's CLI is not the authority, replay now requires the retained ladder command to carry the
**literal tokens the stage passes** (`LADDER_LAUNCH_FLAGS`: depth ladder, coexist, terrain-LOAD)
and to name no experiment that writes or clears Minecraft's depth — a command that lacks them,
or no command, is not the stage's launch and is refused whatever else it spells. What the
command cannot show — a property set through the environment (`ORG_GRADLE_PROJECT_…`, not
retained) — is a stated limit; `gradle.properties` is fingerprinted, so a change there refuses
replay. The two handoff residuals (the manual ladder command, the run count) are fixed.

## Voxy's terrain pipeline in a LOADed pass, judged per pixel (2026-10-09)

**The experiment.** The quad above used a purpose-built pipeline. This one uses **Voxy's own
terrain pipeline** — `VkTerrainRenderer.recordDrawsInRenderPass`, the same shaders, the same
indirect draws, Voxy's own depth state (`GREATER_OR_EQUAL`, depth test on, **depth writes on**)
— recorded into a pass that `LOAD`s Minecraft's colour **and depth**, on the frames the ladder
samples, after the ladder's two readbacks of that frame were requested, with a third readback
of the same band afterwards. It is a **separate probe with its own flag**
(`McNativeTerrainLoad`, `-Dvoxy.native.terrainload=true`, `-PharnessNativeTerrainLoad`), because
the rule from round 15 stands: nothing in the ladder may write Minecraft's depth. It records
only on sampled frames, only after the ladder's readbacks, and Minecraft clears depth every
frame, so the ladder's samples remain Minecraft's own depth; the ladder publishes the probe's
pass count (`terrainLoadDrawsRecorded`) and the gate requires one pass per sample.

**The scene** is `SyntheticTerrain.depthSweep()`: five panels at the same x range, 32·k blocks
away along +Z for k = 1, 2, 4, 8, 16, each 32 UP quads and 64 NORTH quads, seen from a point 8
blocks up at z ≈ 0 looking slightly down +Z, so they form a non-overlapping staircase whose
reverse-Z depths (near 0.1, far 2000, 60°) run from ≈ 2⁻⁸·¹ to ≈ 2⁻¹²·⁷ — on **both sides** of
the bracket (2⁻¹², 2⁻¹⁰] in which the ladder measured Minecraft's overworld terrain. The
projection is Voxy's `VkSceneUniform.perspective`/`lookAt` followed by an NDC affine map that
fits the analytic footprint of the quads' cells inside the ladder band with a 5 % margin and
leaves depth untouched (`McNativeTerrainLoad.fit`; JUnit pins the footprint, the depth
invariance and the two-sided depth split, and a GPU test renders the reference on this machine
and checks that every geometry pixel lies in the band and both depth sides exist).

**The expectation per pixel comes from two independent measurements of the same frame.**
The ladder's first crop gives Minecraft's depth bracket (lo, hi] at each pixel; Voxy's own
render of the same scene with the same matrix into its own target — colour and D32 depth, read
back once at build time — gives Voxy's depth d_V where it has geometry. Voxy's fragment passes
at d_V ≥ d, so: d_V ≥ hi → the pixel must be **exactly Voxy's reference colour**; d_V ≤ lo →
it must be **byte-identical to the previous readback** (the quad's crop, since the coexist
experiment runs in the same launch); lo < d_V < hi → either (counted as undetermined); no
geometry → unchanged. Violations are zero or the sample fails. The retained evidence per sample
is the third crop, its quarter-scale thumbnail (anchored like the ladder's), and the reference
colour and depth crops of the same band (once per frame size and rect, `.ppm.gz` and a
`VXF32` float32 `.f32.gz`); the gate recounts every pixel from those files, reconciles the
twelve counts with the report and with the log line, requires both determinate kinds in at
least one sample, and reconciles the probe's report with the ladder's view of it (pass count,
device, formats: colour 37, depth 126 = `D32_SFLOAT`, which the pipeline declares).

**Measured** (run
[20261009T073850-255416Z](runs/native-evidence/20261009T073850-255416Z/MANIFEST.json), which
replayed 0 in the checkout it was built from): 20 samples, one terrain-LOAD pass each, **495 376 geometry pixels judged with
zero violations** — no pixel showed Voxy where its depth was at or below Minecraft's bracket, none
hid it where its depth was at or above, none held a third colour, none without geometry changed.
Of those pixels 439 452 were expected visible and were, 22 973 were expected hidden and were,
32 951 fell inside Minecraft's bracket and were counted as undetermined (shown or not, both
allowed); 14 of the 20 samples hold both determinate kinds (the near panels over Minecraft's
terrain, the far panel behind it; over sky everything shows). The coexist quad in the same run:
zero violations in 20 samples (2 mixed); the Z direction: reverse-Z, as before. Per-sample
counts are in `ladder/native-terrain-load.json` and the log; the gate's recount is the replay.

**The first run failed its own gate, on chronology, not on pixels (2026-10-09).** The ladder's
sample line is written when the GPU callback runs; the harness's `stage=` line is written when
the stage changes. At draw 1922 the readback was requested during `return` and the callback
logged the sample under `edit`, so the gate's "stage current when the sample was logged" check
refused the run (its 20 terrain-LOAD samples held zero violations; they are not cited as proof
because the run did not pass and was not retained). Repaired, strengthening rather than
relaxing: the ladder now logs a **request line at capture time** (`depth ladder sample
requested at draw N stage=… camera=[…]`); the gate anchors the stage of record to that line,
requires exactly one request per sampled draw, and requires the later sample line to repeat the
request's stage and camera. The probe's final report also lost its scene facts when the scene
was retired at shutdown (`built: false, referenceSet: 0`); the facts of the last built scene
are now kept in statics and published with a separate `live` flag.

**Stated limits.** The depth state is *declared* (`[6, 1, 1]`, `depthStateReadBack: false`),
not read back from pipeline creation as the ladder's is — `VkGraphicsPipeline` builds it and the
probe publishes what the builder was asked for. The scene is synthetic. The verdict is only as
fine as the ladder's brackets: inside a bracket nothing is decided, and the gate counts those
pixels rather than claiming them. A passing result does not authenticate that the GPU executed
the compare, any more than any other retained record does.

## Native instance mode: a world engine without a render path (2026-10-09)

**Why.** The next experiment — real sections through Voxy's terrain pipeline into Minecraft's
LOADed pass — needs real sections to exist. On Minecraft's Vulkan backend `VoxyClient` chooses
no backend (`BACKEND = null`) and therefore never registers the instance factory, so there is no
`VoxyClientInstance`, no `WorldEngine`, no ingest; the interop probe's `ensureRealScene` had
already logged "no Voxy world engine for this level" in that situation. The renderer the
instance would normally bring (`VoxyRenderSystem`) is the GL/interop path, which cannot exist on
Minecraft's Vulkan backend (its constructor calls GL and casts Minecraft's textures to
`GlTextureView`).

**What the flag does.** `-Dvoxy.native.instance=true` (`-PharnessNativeInstance`, default off,
experimental): when Minecraft is not on OpenGL, `VoxyClient` registers the instance factory and
nothing else — no backend, no renderer. `MixinLevelRenderer.voxy$createRenderer` creates the
world engine for the level and returns without a renderer. Everything the instance brings
(storage under the game directory, the ingest service fed by `MixinClientChunkCache` /
`MixinClientLevel` through `WorldIdentifier`, the saving service, the import manager) is
GL-free. The consumers of `IVoxyRenderSystemHolder.getNullable()` already tolerate a null
renderer.

**What is recorded.** `McNativeInstanceProbe` samples at the level-render tail every 60 frames
(and at frame 1): factory set, instance present, engine present and live, the engine's active
section count, whether a `VoxyRenderSystem` exists, ingest enabled; one log line per sample;
`native-instance.json` with the samples and the aggregates. The gate
(`native_instance_result`, stage and replay, required whenever the launch command enabled the
mode in any Gradle form) requires: no backend named, no renderer ever, at least one sample with
an engine, a maximum active-section count of at least one, a live engine with ingest enabled at
the last sample, samples on the interval and in order, aggregates equal to the samples, log
lines equal to the report, no notes. It is part of the **first** native launch (the one that
carries marker, terrain, depth copy), not the ladder launch.

**What it does and does not say.** It says Voxy's instance can run on Minecraft's Vulkan backend
without a render path and ingests sections there. It does not say the sections' content is
right, that anything is drawn, or anything about LoD.

**Measured** (run
[20261009T080812-119439Z](runs/native-evidence/20261009T080812-119439Z/MANIFEST.json), which
replayed 0 in the checkout it was built from): 73 samples over 4 320 frames; the factory, instance and a live world engine
present at every sample from the first; `VoxyRenderSystem` never created; ingest enabled; the
engine held up to 16 active sections (the count rises as chunks arrive and falls as sections
are saved and idled, which is the engine's normal behaviour, so the gate requires a maximum of
at least one and a live engine at the end, not a monotone count). The same run's terrain-LOAD
experiment: 24 samples, zero violations, 17 with both determinate kinds.

## Real sections, Minecraft's own matrix, Minecraft's loaded depth (2026-10-09)

**The experiment.** `McNativeRealLoad` (flag `voxy.native.realload`, `-PharnessNativeRealLoad`,
in the ladder launch, needs native instance mode) takes the frames the ladder hands it — the
ladder now hands each sampled frame to exactly one depth-writing experiment, alternating with
terrain-LOAD, and publishes the hand-off per sample. On such a frame it meshes the **world
engine's real sections** at level 3, radius 4 around the camera (`McNativeRealScene`: the
interop probe's components — REAL atlas, the world's `Mapper`, `VkRealModelBakery`,
`VkRealMesher`, `VkRealSectionUpload`), writes the uniform with **Minecraft's own projection and
model-view** (copied each frame from Sodium's CUTOUT hook, `McNativeCamera`) after the device is
observed idle, renders Voxy's own reference (colour + D32) fence-waited, then records
`recordDrawsInRenderPass` into a pass that LOADs Minecraft's colour and depth (Voxy's
`GREATER_OR_EQUAL`, writes on) and reads it back. Each pixel is judged exactly as terrain-LOAD's.
Every handed sample carries a result: judged, or a reason from a fixed set.

**A GL dependency found and removed on the way.** The first gated run aborted the JVM: Voxy's
`SoftwareModelTextureBakery.setupTexture` read Minecraft's block atlas with raw GL
(`glGetInteger`, `glGetTexImage`), and on Minecraft's Vulkan backend there is no GL context.
The bakery is otherwise software rasterisation. `McNativeAtlas` now copies the atlas (mip 0,
2048×2048, RGBA8) with Minecraft's own `copyTextureToBuffer` and supplies it to the bakery;
samples before it arrives are skipped as `atlas-pending`. The GL path is unchanged.

**Measured** (run [20261009T100957-766731Z](runs/native-evidence/20261009T100957-766731Z/MANIFEST.json), replay 0 in this checkout):
11 samples handed to real-LOAD; one `atlas-pending`, **10 judged with zero violations**. 675 264
geometry pixels judged: **143 425 had to show Voxy and did**, 531 839 fell inside Minecraft's
bracket (undetermined), **0 had to be hidden**. 8 of the 10 judged samples hold pixels that must
appear — over Minecraft's sky, including the `horizon` look at the Voxy-only terrain around
x = 768 (20 610 such pixels). Minecraft's Vulkan projection already produces 0..1 depth (the
probe never had to adjust it); its far plane is 2048 blocks. Four scenes were built (the camera
moved between level-3 sections at `travel` and back).

**What it does and does not say.** Voxy's real meshes, drawn with Minecraft's matrix through
Voxy's terrain pipeline, land in **the same depth space** as Minecraft's scene, at bracket grain:
where Minecraft shows sky, Voxy's terrain appears exactly as its own reference says, and where
Minecraft's terrain and Voxy's coincide the verdict is undetermined, not violated. It does **not**
show occlusion of Voxy by nearer Minecraft geometry: no judged pixel had to be hidden, so that
half of the composition is untested here. It is not LoD selection, traversal, culling,
translucency, lighting or a delivered renderer: one coarse level around the camera, opaque only,
experimental and flagged. The depth state is declared, not read back.

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
6. ~~Which direction of Minecraft's depth value is nearer~~ — measured above: larger is
   nearer (reverse-Z), from two straight-down looks.
7. ~~Whether a draw with Voxy's compare op composes against Minecraft's loaded depth~~ —
   measured above for a known-depth quad, per pixel. Voxy's real terrain pipeline in a
   `LOAD`ed pass is next.

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
