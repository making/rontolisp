# 109 — Named element-wise math functions (numpy ufunc parity) for `linalg:` and `vec:`

**Status: PHASES 1 + 1.5 + 2 DONE; PHASE 3 first release (`maximum`/`minimum`/`clip`/
`relu`, 2026-07-10) DONE. Only the Phase 3 candidates below remain.** The durable
record of everything shipped -- the per-backend kernel decisions, the oracle rules, the
recipe a new member follows -- is `.kb/vec.md` (the unary-ufunc and comparison-select
sections) + `.kb/linalg-simd.md` (the intercepted set). Read those before adding a
member; this file is only the not-yet-decided list.

## Phase 3 remaining (not started)

- `power` (element-wise `expt`): CL `expt` has exact integer/ratio semantics; decide
  whether `linalg:power` is float-only (numpy-like) BEFORE touching it.
- `floor` / `ceil` / `trunc` / `round` element-wise: numpy returns FLOATS; CL `floor`
  returns integers. Needs a semantics decision (float-valued like `ffloor`?) -- do not
  overload the CL names. wasm has native f64.floor/ceil/trunc/nearest, so scalar
  support is cheap; `round` vs f64.nearest ties-to-even needs a look at the CL round
  contract first.
- Maybe, as fused named ops (norm precedent): `sigmoid`, `silu`, `softmax` (decide
  whether the defun does the max-subtraction stabilization -- whatever the defun says
  becomes the contract; `linalg:relu` exists now).

The sibling lineage extending the intercepted set with comparison/indexing members is
`.todo/121`.
