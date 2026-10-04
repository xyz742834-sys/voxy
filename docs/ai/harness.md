# Automated verification and repair workflow

`python3 scripts/verify.py` runs the GPU gates and a real Minecraft scenario, closes
the client, and returns zero only if the selected stages passed. No human world
navigation or log interpretation is required for the listed assertions.

## Development direction and evidence scope

Read [project-goal.md](project-goal.md) before selecting repairs. The owner requires
Voxy within Minecraft's own Vulkan backend on MoltenVK; do not expand or productize
the GL-hosted probe through this repair loop. Preserve it as a diagnostic reference
and prioritize reusable CPU/Vulkan fixes or the native connection.

This runner's live stage sets Minecraft's backend preference to `default` and
observes Voxy's Vulkan enum/probe. It does not prove Minecraft itself uses Vulkan;
the current probe requires Minecraft GL. A complete runner PASS therefore certifies
only its diagnostic scope. A native integration gate must separately verify the
actual Minecraft Vulkan backend, connection route, color/depth and resource lifetime,
and reject OpenGL fallback. Such a gate is not implemented yet.

For an authorized agent repair session within this scope:

1. Read `summary.json` from the path printed by the runner. Follow each failing
   stage's log, per-test diagnostics and live checkpoint evidence.
2. Reproduce the specific failure, make a bounded source fix and add an independent
   regression assertion where appropriate. Keep the backend and GPU contracts in
   [constraints.md](constraints.md) and [gpu-contracts.md](gpu-contracts.md).
3. Rerun a targeted stage with `--only gpu` or `--only live` while iterating.
4. Run the full command and the normal build after the final source changes. Update
   the living docs with measured outcomes and explicit remaining coverage gaps.

The runner executes verification; the agent performs the code repair. It does not
contain hardcoded speculative fixes, invoke another AI process, or turn a failing
check into a pass by broadening diagnostic suppressions. Lack of a usable GPU,
validation layer, graphical session or dependencies is a failed gate with evidence.

## Evidence format

`summary.json` records host, git revision, dirty status, stage commands/exit codes,
wall-clock duration, gate failures and selected scope. `source-sha256.json` fingerprints
source/build/runner files and `source/` preserves those inputs;
`tracked-changes.patch` records tracked working changes.
Per-stage logs and JUnit XML distinguish failed tests, unexpected skips, unexpected
diagnostics and the one documented descriptor-sync blind spot.

`live-result.json` is written after every checkpoint and at completion. It records
frame/geometry/depth metrics, world identity, position/dimension and edit mesh
versions. A crashed or timed-out client never has a successful completed result.
Every run has its own new game directory, retained for diagnosis; no prior save is
modified and no runner cleanup deletes historical evidence.

## Required standalone visual/recovery evidence

The GPU stage now also requires `VkVisualRecoveryTest` and the runner's independent
`scripts/pixel_oracle.py` check. `visual-recovery/` contains literal-reference,
actual and diff PNGs, raw float depth, per-fixture JSON and `pressure.json`.
Missing/invalid pixels or metrics fail the overall GPU stage even if Gradle exits zero.
Four controls establish detection of mirrored placement, missing draws, wrong atlas
color and wrong depth. Successful runs require zero differences for the correct
opaque fixture and for all three recovered images.

The pressure test uses the production `VkGeometryAdmission` policy with an actual
3 KiB arena and `NodeManager`: fill, reject a 2 KiB demand after child reclamation,
release another root, re-request the same demand, upload and render again. It checks
actual freed bytes, protected roots, new requests, exhaustion clearing, pixel/depth
recovery and full release across three cycles. This is a reusable standalone Vulkan
gate. It does not expand GL-hosted integration or certify native/live-world recovery.

## Limits

The live harness tests the diagnostic Vulkan path on macOS. CPU meshing remains
synchronous, the camera region is rebuilt when left, and the full GL async streaming
architecture is not ported. Eleven screenshot checkpoints and renderer assertions
cover a bounded live scenario; they do not prove live-world pixel parity or arena
recovery. Standalone analytic pixel/recovery evidence is separately scoped above. Increase dwell time for longer runs, and extend scenarios/assertions when
new requirements call for those guarantees.

Memory-binding fix reference: [IOSurface import](https://docs.vulkan.org/refpages/latest/refpages/source/VkImportMetalIOSurfaceInfoEXT.html)
does not carry the implicit bound-memory guarantee documented for
[Metal texture import](https://docs.vulkan.org/refpages/latest/refpages/source/VkImportMetalTextureInfoEXT.html).
The implementation now allocates/binds memory and releases it with the image.
MoltenVK's [image implementation](https://github.com/KhronosGroup/MoltenVK/blob/main/MoltenVK/MoltenVK/GPUObjects/MVKImage.mm)
keeps the IOSurface texture as backing when memory is bound; the existing interop
image-equivalence tests verify that sharing still works on this host.
