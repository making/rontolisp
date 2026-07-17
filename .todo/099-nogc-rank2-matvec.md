# 99 — `--no-gc` rank-2 packed layout + `vec:matvec` (native f32x4/f64x2 GEMV)

**STATUS: DONE 2026-07-10** (uncommitted, worktree `feat/packed-float-array`). The design
sketch this file used to hold shipped as written; the record below is what actually landed
plus the explicitly-out-of-scope residuals. Full mechanics: `.kb/vec.md` ("Acceleration
layer 2") and `.kb/no-gc-scalar-wasm.md`; user docs `doc/{en,ja}/guides/simd-acceleration.md`.

## What landed

- **Rank-2 packed matrix layout**: new `Ty` kinds `F64MAT`/`F32MAT` — an i32 pointer to
  `[rows:i32][cols:i32][rows*cols f64|f32 row-major]`. A DISTINCT static kind, not a rank
  tag: the rank-1 `[count][data]` `F64VEC`/`F32VEC` layout is byte-identical to before
  (`NoGcWasmCompilerTest` 62/0 with the pre-existing structural pins untouched).
- **Minimal rank-2 surface (exactly what GEMV needs)**: `make-array` with a rank-2 dims
  spec — `(list d n)` (runtime exprs) or `'(d n)` (literal), parsed by `dimExprs`, built by
  `compileMakeMatrix` (`:initial-element` supported like rank-1); two-subscript
  `aref`/`%aset` (row-major `i*cols + j`, `emitMatElementAddr`) and flat 1-subscript
  `row-major-aref`/`%row-major-aset`. `typeOf`/`collectCalls` walk the dim EXPRESSIONS (a
  `(list d n)` head is never treated as a call to the unsupported `list`).
- **`vec:matvec` + `vec:matvec-into`** (`compileSimdMatvec`): one dot per row —
  `WasmVecLoops.simdDot` under `--simd` (f64x2/f32x4; an f32 row accumulates in f32 lanes,
  the todo-106 contract), `WasmVecLoops.scalarDot` without (module carries no 0xFD, runs
  under `wasmtime -W simd=n -W relaxed-simd=n`, verified). The row cursor restarts from a
  maintained row pointer each iteration (the dot emitters clobber their pointer locals).
  Result = fresh rank-1 vector of length `rows`, width = W's width; x/out must match W's
  width (`compileCoerced` mismatch = the incompatible-types error). `SIMD_UNSUPPORTED_NO_GC`
  is gone (empty). API unchanged: `(vec:matvec W x)`, no explicit-dims form.
- **`matvec-into` alias guard**: out==x or out==w traps at runtime via pointer equality +
  `unreachable` (this backend has no error channel) — the analog of wasm-GC's `ref.eq` trap
  and the interpreter/JVM error. Verified manually (wasmtime reports "wasm `unreachable`
  instruction executed").
- **Tests**: `NoGcWasmCompilerTest` — rank-2 make-array/aref/aset structure, matvec v128
  opcodes under `--simd` (f64x2/f32x4 splat + mul + extract_lane) / no 0xFD without, -into
  alloc count (3 = matrix + 2 constructors) + trap sequence pin, width-mismatch error,
  rank-1-W clear error, rank-3 make-array clear error (the old rank-2-error pin rewritten).
  `WasmLispCompilerIntegrationTest.noGcRunsMatvecGemvOverTheRank2PackedMatrix` — both
  widths x both lowerings + `--optimize`, d=3/n=5 (f64x2 pairs + odd tail) and d=2/n=6
  (f32x4 quad + 2-element remainder), hand-computed exact-integer expectations
  cross-checked manually against interpreter + wasm-GC (scalar and `--simd`) oracles.

## Out of scope / residuals (deliberate)

- **Rank-2 `#d((...))`/`#f((...))` literals** stay a clear compile error (a GEMV `W` is
  built via `make-array` + a fill loop; literals are a later nicety).
- **Rank >= 3** and general (boxed) rank-2 arrays: clear compile errors, unchanged design.
- **`array-dimensions`/`array-dimension`/`array-rank` on `--no-gc`**: still unsupported
  (`array-dimensions` returns a cons list, which the backend lacks; the macro-expanded
  `array-dimension` lowers to it). GEMV reads rows/cols from the block header natively, so
  nothing needed them. A header-reading `array-dimension` with a literal axis would be easy
  if ever wanted.
- **The existing examples cannot gain a `no-gc` backend row** (checked 2026-07-10):
  `examples/ml/tiny-llm.lisp` uses `linalg:` (does not compile under `--no-gc`);
  `examples/ml/simd-gemv.lisp` uses cons lists (`iterate` accumulates a list), `defparameter`
  globals, `format` and top-level run forms — all ineligible (`--no-gc` top level = defuns +
  `wasm-export` only). Instead a DEDICATED reactor example landed (same session, user ask):
  **`examples/ml/simd-gemv-nogc.lisp`** — simd-gemv's inner loop as a `fingerprint(n)` export
  (LCG state threaded through a local instead of the global; `matvec-into`/`scale-into` keep
  the bump heap at exactly three blocks), registered `backends: [no-gc, no-gc-simd]` with a
  NEW `no-gc-simd` compile token in `ExamplesE2eTest`/`examples.yaml`. Verified: BOTH
  lowerings reproduce simd-gemv.lisp's fingerprints exactly (steps 1-10 =
  0 14 82 126 14 140 126 79 134 175, step 100 = 85; scalar build runs under
  `wasmtime -W simd=n -W relaxed-simd=n`); Apple M4 at 20000 steps: scalar ~600 ms vs
  `--simd` ~120 ms. Docs: examples/README.md row + `doc/{en,ja}/guides/simd-acceleration.md`
  "Runnable examples".
- `--no-gc` GEMM (matmul) remains linalg-only territory; `linalg:` still does not compile
  under `--no-gc` at all.
