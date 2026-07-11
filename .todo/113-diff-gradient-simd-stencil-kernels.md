# 113 — `--simd` interception for `linalg:diff` / `linalg:gradient` (stencil kernels)

**Status: NOT STARTED — design notes from the 2026-07-11 measurement session.**

`linalg:diff` and `linalg:gradient` (added 2026-07-11, numpy `np.diff`/`np.gradient`
parity) are deliberately NOT `--simd`-intercepted, alongside `emap`/`det`/`inv`/
`array-equal` (`.kb/linalg.md`). This todo records what interception would take and
why it was deferred, so the decision can be revisited with the facts in hand.

## Measured baseline (wasm-GC, 100k-element vector x 100 iterations, wasmtime)

| op | scalar build | `--simd` build |
| --- | --- | --- |
| `linalg:diff` (not intercepted) | 0.92 s | 0.92 s (parity) |
| `linalg:sub` (intercepted, same-size element-wise) | 0.96 s | 0.09 s (~10x) |

- **No pessimization**: the pre-todo-107 failure mode (vblock `_v_get`/`_v_set`
  accessor cost with no v128 payoff) did NOT reproduce for the scalar `diff` defun
  under `--simd` — allocation dominates. So leaving them unintercepted is safe.
- **The upside is real**: `diff` is "a `sub` of the input with a one-shifted view of
  itself", so ~10x is on the table at this size. There is NO user-level workaround:
  numpy escapes via slicing (`a[1:] - a[:-1]`) and linalg has no slice op.

## Design sketch (todo-107/109 pattern: three kernel sites + null-decline)

Kernels in the usual three places — `eval/LinalgSimd` (+ a `LinalgSimdKernels`
stencil method), the JVM `JvmSimdVectorTemplate` bridge (+ `JvmLinalgSimdCompiler`
case), `WasmLinalgSimdRuntimeBuilder` (+ `WasmLinalgSimdCompiler` case). The linalg
partial-function protocol applies unchanged: a kernel returns null (declines) for
boxed arrays / plain numbers / rank or length it does not handle, and the call site
falls back to the scalar defun in `linalg.lisp`, which stays the oracle (error
messages included).

The new ingredient vs the existing element-wise kernels is the **stencil (shifted
operand) load**:

- **Interpreter / JVM Vector API**: trivial — `fromArray(SPECIES, a, i + 1)` is a
  legal offset load; the loop is the `sub` loop with one operand shifted.
- **wasm-GC vblock repr**: a shifted read crosses lane-group boundaries, so it needs
  the `i8x16.shuffle` window pattern `matvec` already uses (`.kb/linalg-simd.md`,
  lane-form GEMM notes). The trailing zero-sentinel group means the shifted window
  never reads out of bounds — same invariant matvec relies on.
- **Precision**: element-wise only, NO reductions — bit-identical to the scalar
  defun at both widths is achievable and REQUIRED (the todo-106 f32-reduction
  contract is irrelevant here; nothing accumulates). This also holds for the
  non-uniform gradient interior formula (per-element independent float ops).

Scope order if picked up:

1. `diff` order 1 (the shifted `sub`) — `diff n` iterates the same kernel n times
   (the scalar defun already loops `%la-diff-1`, so intercept `%la-diff-1`'s shape,
   or the whole `diff` with an n-loop in the kernel; the former keeps the oracle
   structure).
2. `gradient` uniform-spacing path — interior stencil `(f[i+1] - f[i-1]) / 2h` plus
   two scalar edge writes; one shifted-by-2 window.
3. `gradient` coordinate-vector path — a 3-operand stencil (f and x both shifted
   twice) with mul/div lanes; heavier, defer until a use case shows up.

## Why deferred

diff/gradient's typical uses (preprocessing, analysis, teaching examples) are not
hot loops, parity under `--simd` was measured (no harm), and the wasm shuffle-window
stencil is real work. If `vec:` ever grows stencil kernels (e.g. convolution), ride
that machinery instead of building it here first.
