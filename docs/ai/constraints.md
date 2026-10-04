# Constraints

Invariants and rules an agent working in this repo must not accidentally violate. Each
entry says *why*, so you can judge edge cases rather than pattern-match the rule.

## Vulkan delivery must use Minecraft's Vulkan backend

The owner's 2026-10-04 decision in [project-goal.md](project-goal.md) governs future
Vulkan work: Minecraft and Voxy must both run Vulkan, through MoltenVK on macOS.
New integration must not require a GL context, GL texture IDs/casts or GL composition.
Do not expand/productize the IOSurface/CGL diagnostic route or add a GL fallback
requirement as a completion path. Existing GL-hosted passes are not native acceptance.

Preserve the established GL renderer, shared CPU/shader invariants and diagnostic
evidence. Reusable CPU/Vulkan correctness work remains relevant; do not interpret
this direction as a ban on shared code or permission to delete all GL-related files.
The first integration priority is a minimal draw on Minecraft's Vulkan device with
correct color/depth, submission ordering and resource ownership.

## Historical reports are immutable

`docs/*.md` phase reports (45 files) are a chronological record — several already
contain corrections appended below an earlier conclusion, by design. **Do not edit a
historical report to "fix" a stale claim.** If something in one is outdated, either:
add a new report, or fold the current understanding into
[current-state.md](current-state.md). This preserves the audit trail that later reports
(and `docs/ai/bootstrap-audit.md`) rely on to say "X superseded Y".

## GL and Vulkan share source in non-obvious places

- `#ifdef VULKAN` blocks inside `shaders/lod/hierarchical/*.glsl` (notably
  `queue.glsl`) mean the **same file** compiles differently for each backend. A change
  meant for Vulkan-only behavior needs the guard; a bug found in one backend's compiled
  output may not exist in the other's.
- Root-level `lod/*.glsl` includes (`quad_format.glsl`, `section.glsl`,
  `block_model.glsl`, etc.) are shared verbatim by GL and Vulkan shaders. A layout
  change here must be checked against **both** `gl46/` and `vk/` consumers, and against
  the Java-side struct writers — see [gpu-contracts.md](gpu-contracts.md).
- `client/core/rendering/hierachical/` (`NodeManager`, `NodeStore`) is shared Java
  between backends. Note the package spelling is `hierachical` (missing an `r`) — this
  is the actual on-disk/on-classpath spelling; don't "fix" it in isolation, since it's
  referenced throughout both backends and would be a large, unrelated rename.

## Vulkan is Apple/macOS-shaped, not generic

`VkContext` assumes unified memory (`DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT` on
every buffer), picks the **first** physical device with no selection logic, and the
whole interop layer (`client/core/vk/interop/`) is IOSurface/CGL-specific, i.e.
macOS-only. Do not "generalize" these without an explicit task to do so — they are
current, intentional scope limits, not oversights waiting to be cleaned up.
These describe the diagnostic implementation, not a requirement to retain an
independently created device or IOSurface/GL interop when connecting to Minecraft's
Vulkan backend. Device borrowing and direct Vulkan composition follow the project
goal; unrelated cross-platform generalization remains outside that goal.

## Binding numbers are pipeline-local

Descriptor binding numbers (e.g. "binding 3") are **not** global resource IDs — they
are meaningful only within one pipeline's layout, and different pipelines reuse the
same numbers for different resources. See the binding table in
[gpu-contracts.md](gpu-contracts.md). All current pipelines use descriptor set 0;
`VkShader` rejects a nonzero descriptor set at build time.

## Descriptor/reflection guards are partial, not comprehensive

`VkShader` compares descriptor *types/counts/stage visibility* and rejects mismatches,
and `VkAutoBindingShader` avoids descriptor mutation between recorded dispatches via
prebuilt variants + submission-generation checks. It does **not** compare Java-side
struct offsets against actual SPIR-V-reflected member layouts, does not universally
assert `unboundBindings()` inside `bind()`, and does not handle descriptor arrays,
same-type aliasing across stages, or malformed reflection input. Don't treat "the build
passed" or "`VkShader` didn't throw" as proof a Java↔shader layout is correct — verify
the actual byte layout (see [gpu-contracts.md](gpu-contracts.md)).

## Barriers must follow the actual producer/consumer graph, not a mechanical GL→Vulkan translation

The dependency chain documented in [gpu-contracts.md](gpu-contracts.md) (host
writes→shader reads, previous-table reads→table overwrite, prep→dispatch/cull reads,
fragment visibility writes→compute, prefix writes→indirect/vertex reads, depth
attachment→HiZ, HiZ mip→mip, HiZ→traversal, resolve→interop) is the thing that must stay
correct. **Do not relax or remove a `CONSERVATIVE`/broad barrier to "clean up" or chase
performance without validation-layer evidence that a narrower barrier is sufficient.**
D3's attachment LOAD transitions were fixed on 2026-10-04, but the
descriptor-SSBO blind spot noted in [testing.md](testing.md) remains — don't add more by
assumption.

## `VulkanTestSupport.requireVulkan()` swallows more than "no GPU"

It catches **any** `Throwable` during Vulkan init and turns it into a JUnit assumption
skip. A genuine regression in `VkContext` init can present as "test skipped," not "test
failed." When adding Vulkan-dependent tests or debugging unexpected skips, check
whether the skip is really "no supported GPU here" before trusting it.

## Test coverage gaps are real, not incidental

The 2026-10-04 runner drives a real-world smoke scenario and the new table regression
covers changing GPU section counts against stale host counts. These do not prove
full hierarchy correctness, live pixel parity, or live forced-reclaim/exhaustion recovery.
The additional standalone analytic color/depth and 3 KiB arena recovery gates cover
their explicit opaque fixture and admission policy, not the missing native integration
or arbitrary live-world scenes. Keep these evidence scopes separate.
The descriptor-SSBO negative control still aborts as skipped; only the fill-buffer
control proves synchronization validation is active. The runner reports the blind
spot and rejects other skips. See [testing.md](testing.md).

## Do not treat "Gradle build succeeded" as "Vulkan validated"

CI (`.github/workflows/`) runs on `ubuntu-latest` with no configured GPU/validation
lane. A green CI run proves compilation and the GPU-independent unit tests, nothing
about Vulkan/interop correctness. Vulkan validation only happens when someone runs the
test suite locally with `-PvkLibname=... -PvkValidation=true -PvkSyncEnv=true` on real
(or Lavapipe) Vulkan hardware — see [testing.md](testing.md). "BUILD SUCCESSFUL" or "ALL CHECKS PASSED" alone is not a strict validation gate:
plain tasks still check selectively. The unexpected baseline diagnostics were fixed
on 2026-10-04; `scripts/verify.py` now rejects unexpected per-test/setup/teardown and
interop/live diagnostics. Use it to prevent D6-style false-green outcomes.

## Don't promote a historical report's opening claim without reading its corrections

Several `docs/*.md` reports state an initial conclusion, then correct or retract part
of it later in the same file (or in a subsequent report). `docs/ai/bootstrap-audit.md`'s
"Superseded decisions and report/code inconsistencies" section lists the currently-known
ones. When citing a historical report to justify a design decision, check whether it's
been superseded before relying on it.

## Scope discipline for audit/bootstrap-style tasks

If asked to do another audit-like pass: match the existing convention of
**observed / source-confirmed / likely-risk / historical** evidence labels, don't mix
fixes into an audit-only change, and don't overwrite `/tmp` evidence claims as if they
were reproducible from the repo alone (some historical benchmark numbers came from
external tooling not in this repo — flag that rather than re-deriving them).

## Diagnostic update ownership

The active Vulkan scene owns its engine's dirty callback and detaches it at teardown.
Callbacks may run on ingestion threads: they must only forward events/enqueue keys.
Node mutation, remeshing and uploads remain in bounded post-fence request service.
Do not install a second renderer's callback on the same engine or touch the local
watch-enumeration map from producer threads. `WorldEngine.markDirty` captures the
volatile callback once to avoid a concurrent detach null-dereference.
