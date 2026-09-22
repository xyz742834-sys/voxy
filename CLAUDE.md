# Voxy

LoD terrain renderer for Minecraft (Fabric mod), with two independent GPU backends:
an established OpenGL renderer and a diagnostic-scope Vulkan/macOS port. Persistent
AI-agent context lives under `docs/ai/` — read it before making non-trivial changes:

- [docs/ai/current-state.md](docs/ai/current-state.md) — what works, what's incomplete,
  known defects (D1–D6), current integration status. Start here.
- [docs/ai/architecture.md](docs/ai/architecture.md) — CPU/shared boundary, backend
  selection, the Vulkan frame sequence, GL/Vulkan interop, shader organization.
- [docs/ai/repo-map.md](docs/ai/repo-map.md) — subsystem → source path → relevant
  historical report.
- [docs/ai/constraints.md](docs/ai/constraints.md) — invariants and rules not to
  accidentally violate (shared GL/Vulkan source, barrier discipline, binding-number
  scoping, etc.).
- [docs/ai/testing.md](docs/ai/testing.md) — verification levels and exact commands,
  and what each one does/doesn't prove.
- [docs/ai/gpu-contracts.md](docs/ai/gpu-contracts.md) — Java↔shader buffer layouts,
  descriptor bindings, push constants, barrier chains, GPU-lifetime assumptions.

`docs/*.md` (45 files, outside `docs/ai/`) are historical phase reports — evidence and
rationale, not current specs. Never edit them; see
[docs/ai/repo-map.md](docs/ai/repo-map.md) and
[docs/ai/bootstrap-audit.md](docs/ai/bootstrap-audit.md) for how to read them.

Keep `docs/ai/*` current as the source of truth changes; this file should stay short.
