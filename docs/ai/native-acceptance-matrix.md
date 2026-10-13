# Native acceptance matrix (after the round-28 review of record)

Living. Scope: Voxy on Minecraft's own Vulkan backend (macOS/MoltenVK) under `voxy.native.render`.
The round-28 Codex review of record ([runs/native-integration-review-r28-record.md](runs/native-integration-review-r28-record.md),
frozen `6e8a35a0`, REDESIGN) is the source for every open row. DELIVERY-BOUNDARY (normally enabled
delivery) is separate from default-on readiness; neither is accepted. `safe_for_normal_play=true`
in its JSON is the flags-unset dormant scope only.

Status: **E** = existing retained evidence covers it (within its stated scope); **R** = repaired
in source, needs one fresh bound native run; **M** = missing check, implementable; **O** = owner
decision implementation cannot settle.

| # | Area | Status | Existing evidence / repair | Missing (finite) |
|---|------|--------|----------------------------|------------------|
| 1 | Clear-pixel composition, depth write, cutout point | E | Ladder bands (12 judged hierarchy samples, zero violations), reprojection | Downstream depth users (entities, weather) — visual reference |
| 2 | Far / fade / night | E | Run `20261013T013657-855541Z`: 3553 far, 69 faded, 2357 night pixels judged | Floors are 1 pixel (row 13) |
| 3 | Water / translucency | E (partial) | Horizon band is ocean; judged against Voxy's GL rule | Mixed opaque/translucent ordering reference |
| 4 | Native updates (visibility ingest → mesh) | R | Probe block at (40,118,72): `native_update_problem` requires, at `edit` and `remove`, the commanded block change, ≥1 visibility-change ingest of its chunk section and a later native mesh revision in one scene | Pixels not observable this close (Voxy only where MC depth is clear); sustained edits / rapid travel workload |
| 5 | Worker shutdown bound | R | `VkMeshWorkers`: 250 ms bound, per-task world reference, deferred bakery/mesher free, leaked count in the report (gate requires 0); 7 CPU tests incl. stuck/interrupted/stale/cancelled | Timeout path observed live (not inducible without a fault hook) |
| 6 | Gate guard coverage | R | streaming=False and within-size-run growth: focused tests, each predicate's removal detected | — |
| 7 | Living context | R | current-state / project-goal / three code comments corrected | — |
| 8 | Lifecycle soak, pressure reclaim, injected submit failure | E | 4-cycle product + pressure soaks, one injected failed submit | Long session, rapid travel; real device loss is not testable here |
| 9 | Kill switch / config off-on | E (CPU) | `render_disable_probe`, JUnit | Live menu toggle and startup kill launch |
| 10 | Lightmap latency across dimension changes | E (partial) | Per-scene first frame uses the cached lightmap | First-ever scene synthetic until first copy — measure or accept |
| 11 | SSAO BETTER/BEST | O | BASIC ported; BETTER/BEST run as BASIC with one note | **Owner:** accept the BASIC fallback, or require BETTER/BEST (they read Minecraft's depth: new work) |
| 12 | Performance / memory budget | O | Product p95 CPU 2.0 ms, scene GPU 3.5 ms; 730 MB buffers + 599 MB textures at 1708×960 (option B capacities) | **Owner:** set budgets, or accept "measured, not budgeted" for default-on |
| 13 | Allocation tolerances 5 % / 1 %, far/fade/night floors of one pixel | O | Measured runs are flat per size; samples exceed floors by 69–3553× | **Owner:** keep, or tighten (e.g. floors from measured minima); not changed without evidence/decision |
| 14 | GL path regression with the new mixins | M | Mixins inert flags-unset (source review) | One ordinary GL launch on the candidate |

Next native run (one, when the coordinator grants a window) binds rows 4–7 and must pass the
update observation in the product, pressure and inject launches.
