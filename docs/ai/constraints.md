# Constraints

Invariants and rules an agent working in this repo must not accidentally violate. Each
entry says *why*, so you can judge edge cases rather than pattern-match the rule.

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
The codebase already has one open synchronization-validation gap (D3, and the
descriptor-SSBO blind spot noted in [testing.md](testing.md)) — don't add more by
assumption.

## `VulkanTestSupport.requireVulkan()` swallows more than "no GPU"

It catches **any** `Throwable` during Vulkan init and turns it into a JUnit assumption
skip. A genuine regression in `VkContext` init can present as "test skipped," not "test
failed." When adding Vulkan-dependent tests or debugging unexpected skips, check
whether the skip is really "no supported GPU here" before trusting it.

## Test coverage gaps are real, not incidental

Per [testing.md](testing.md), there is currently no automated multi-frame hierarchy
test exercising changing section counts (the exact shape of defect D1), no
disconnect/reconnect or world-identity test (D5's territory), and the
descriptor-SSBO negative control (`VkBarriersTest.missingBarrierIsDetected`) produces
no hazard on this stack and is **skipped** via `Assumptions.abort`, even with validation
on — only the fill-buffer control (`plainBufferHazardIsNowDetected`) actually passes.
Don't cite passing or skipped tests in these areas as evidence of correctness — they're
not exercising the failure mode.

## Do not treat "Gradle build succeeded" as "Vulkan validated"

CI (`.github/workflows/`) runs on `ubuntu-latest` with no configured GPU/validation
lane. A green CI run proves compilation and the GPU-independent unit tests, nothing
about Vulkan/interop correctness. Vulkan validation only happens when someone runs the
test suite locally with `-PvkLibname=... -PvkValidation=true -PvkSyncEnv=true` on real
(or Lavapipe) Vulkan hardware — see [testing.md](testing.md). Even then, "BUILD
SUCCESSFUL" or "ALL CHECKS PASSED" is not "clean validation": both the JUnit suite and
`interopCompositeCheck` currently pass while emitting unsuppressed diagnostics (D6).

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
