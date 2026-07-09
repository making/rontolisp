# 107 — `linalg:` kernel interception (`--simd` / v128)

**Status: Tier 1 + Tier 2 + Tier 3 DONE on all three eligible backends (2026-07-10).**
The mechanics, the precision contract and the benchmarks now live in **`.kb/linalg-simd.md`**;
`.kb/vec.md` and `.kb/linalg.md` point at it, and `CLAUDE.md` has the one-paragraph summary.
What is left below is only what did NOT ship. **Do not delete this file yet** — the residual
items are real.

## What shipped

Fifteen members intercepted — `add` `sub` `mul` `div` `sum` `norm` `amax` `amin` `argmax`
`argmin` `trace` `transpose` `reshape` `dot` `outer` — on the interpreter (`eval/LinalgSimd`
+ `LinalgSimdKernels` + `Target_LinalgSimd`), the JVM (`JvmLinalgSimdCompiler` +
`JvmSimdVectorTemplate.la*`, one bridge) and wasm-GC (`WasmLinalgSimdCompiler` +
`WasmLinalgSimdRuntimeBuilder`, 15 emitted functions after the `vec:` block).
`mean`/`matmul`/`flatten`/`solve` accelerate transitively. `emap`/`det`/`inv`/`array-equal`
never do. `--no-gc` cannot compile `linalg:` at all.

The **regression is gone** — that was the whole point:

| wasm-GC, 40000-element `#f`, 60 reps | scalar | `--simd` before | `--simd` after |
|---|---|---|---|
| `linalg:add` | 215 ms | **236 ms** (slower!) | **1 ms** |
| `linalg:dot` | 119 ms | **138 ms** (slower!) | **1 ms** |

native interpreter: `linalg:add` 1435 → 1 ms, `linalg:dot` 1605 → 0 ms.
JVM (GraalVM) steady state, 2000 × 40000 `#f`: `linalg:add` 112-122 → 11-33 ms,
`linalg:dot` 48-49 → 11-12 ms. All nine samples printed, non-overlapping.

Three design decisions worth not re-deriving:

1. **The declined-input protocol.** A linalg kernel is PARTIAL and returns null for anything
   it cannot read; the call site then runs the scalar defun over the SAME temps. That is what
   keeps `linalg.lisp` the single source of truth (general arrays, mixed widths, plain
   numbers, the exact error messages) without duplicating it. `array-equal` is not
   intercepted because it legitimately returns nil, colliding with the sentinel.
2. **`ikj`, not a transpose.** The todo used to say GEMM "needs a pre-transpose or tiling".
   It does not. Rewriting the oracle's `ijk` as `ikj` makes `b`'s rows contiguous AND visits
   `k` in the oracle's own summation order, so the double-width result is bit-identical.
3. **The matrix product is exempt from the todo-106 reduction contract**, on principle: you
   accumulate in the lane element type only when the lanes ARE the reduction axis. `ikj`'s
   lanes run across the output row, so the accumulator width is free and `double` is both
   more accurate and free of `convert(F2D)`. `laMatmulF` keeps a `double[]` accumulator row.

## Left undone

- **wasm-GC `dot` (v.M / M.M), `outer` and `transpose` are element loops, not lane loops.**
  They go through `_v_get`/`_v_set` (de-boxed, several times faster than the defun, but no
  v128). A lane form needs the `i8x16.shuffle` row window `WasmVecSimdRuntimeBuilder.
  emitRowGroup` already has, plus a lane-aligned scratch vblock to accumulate a row into,
  then a `_v_set` copy-out per element (O(n·p) writes against O(n·m·p) flops — cheap). Worth
  doing only if a wasm GEMM gets hot.
- **The residual wasm-GC penalty is intrinsic and is now documented, not fixed.** A linalg
  program dominated by `emap` / `inv` / `transpose` still pays `_v_get`/`_v_set` under
  `--simd`. A `v128` can only be read out of an `(array (mut v128))`, never out of an
  `(array (mut f32))`.
  - Un-costed alternative worth one measurement: **keep `(array (mut f64))`/`(array (mut
    f32))` under `--simd` and gather lanes** with 2 (f64) or 4 (f32) `array.get` +
    `replace_lane` per group. That deletes the representation switch — and with it EVERY
    un-intercepted regression, `emap` and `inv` included — at maybe 2-4× the kernel cost.
    It contradicts todo-105's choice. Measure before believing either side.
- **`>` disagrees across backends on `-0.0` / `NaN`, and always has.** Interpreter `>` is
  `Double.compare` (a total order, `(> 0.0 -0.0)` → `t`); JVM `>` is `DCMPL` and wasm's is
  `f64.gt` (both IEEE, → `nil`). Each `amax`/`amin`/`argmax`/`argmin` kernel reproduces its
  OWN backend's comparison, so each is bit-identical to its own defun — but the three defuns
  already disagree with each other. **Pre-existing, unrelated to `--simd`, deserves its own
  todo.** Never put a `-0.0` case in `ci-spec.yaml`. See the table in `.kb/linalg-simd.md`.
- A possible `emap` special case when `f` is a *known builtin* (`#'abs`, `#'sqrt`, `#'exp`).
  `linalg:emap #'silu` in `examples/ml/tiny-llm.lisp` is a user lambda and would not benefit.

## A process note, paid for once

While writing this up, an `ls doc/en/guides/` ran in the **main repo** rather than in this
worktree, so `simd-acceleration.md` -- which exists only on `feat/packed-float-array` --
looked missing, and "the `--simd` guide has never existed" nearly shipped as fact in three
files and a commit message. Check for a file's existence from inside the worktree. The same
`cd` discipline the benchmarks need applies to `ls` and `grep`.

## Verify (what was run, 2026-07-10)

`./mvnw spring-javaformat:apply test` (3055, incl. the 22 + 14 new unit tests) ·
`WasmLispCompilerIntegrationTest` on Docker + wasmtime (619, incl. 5 new linalg cases) ·
native `CiSpecE2eTest` (736) and `ExamplesE2eTest` (92) with `-Drontolisp.binary` ·
`./mvnw -Pweb compile` (the `Target_LinalgSimd` substitution) · `./mvnw javadoc:jar` (only
the known `Version` error) · `DocExamplesTest` (415) · `--component --simd` and
`--simd --optimize` by hand · `examples/ml/{tiny-llm,linear-regression,heat3d,deep-digits}`
byte-identical with and without `--simd` on all three backends.
