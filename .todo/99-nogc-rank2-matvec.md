# 99 — `--no-gc` rank-2 packed layout + `vec:matvec` (native f32x4/f64x2 GEMV)

**Motivation:** `vec:matvec` (GEMV, `y[i] = dot(row_i of W, x)`) is the one `vec:` member the
`--no-gc` scalar backend rejects. JVM `--simd` runs it for both f32 and f64
(`JvmSimdVectorTemplate.simdMatvec` / `matvecF`), and it is the dominant kernel in llama2-style
inference (every projection / FFN / classifier is a GEMV — the `.todo/98` at-scale payoff). Now
that `vec:` and `linalg:` are single-float-complete across all backends (todo-97 + the vec
constructor follow-on), the `--no-gc` GEMV gap is the remaining hole in the `vec:` surface on the
scalar backend.

Today it is a clean compile error: `NoGcWasmCompiler.SIMD_UNSUPPORTED_NO_GC = {VEC_MATVEC,
VEC_MATVEC_INTO}` throws "'matvec' is GEMV over a rank-2 matrix, but --no-gc packed vectors are
rank-1 only".

**Update 2026-07-09 (wasm-GC `--simd` GEMV landed; todo-101, re-based on GC arrays by todo-105).**
wasm-GC `--simd` now ships GEMV. Its row walk is `WasmVecSimdRuntimeBuilder.emitMatvecRows`, but it
is no longer directly reusable: it walks GC lane-group indices and reads an unaligned row with
`i8x16.shuffle`, where `--no-gc` walks a raw pointer and would keep its scalar tail. What transfers
is the SHAPE (per-row dot, `res` demoted for f32) plus `WasmVecLoops.simdDot` as the per-row loop.
The LAYOUT half of this todo is what is actually left on `--no-gc`: give it a rank-2 block and read
`d`/`n` out of it, then drive `simdDot` once per row over the row pointer.
wasm-GC gets away with rank-1-only blocks because the `$farray` struct carries `dims` on the GC
heap; `--no-gc` has no struct, so the dims must go in the block header.

## The real blocker: `--no-gc` is rank-1-only by design

The scalar backend has **no rank-2 packed layout**. Every array path enforces rank-1:
- `requireRank1Dims` (~L2342): a `make-array (list d n)` (rank>=2) is a clear compile error.
- `compileFloatArrayLiteral` (~L2157): a rank>=2 `#d(...)`/`#f(...)` literal is a compile error.
- `compileAref`/`compileAset`: exactly one subscript allowed (more => rank>=2 => error).

A `--no-gc` packed vector is a rank-1 `[count:i32][count f...]` linear block (`F64VEC`/`F32VEC`),
so there is no row-major rank-2 layout for `matvec` to read `W`'s rows from. This is deliberate
(the scalar backend trades generality for a plain MVP module); GEMV is the first real need for
rank-2.

## Approach (sketch — re-ground before starting; line numbers drift)

1. **Add a rank-2 packed block layout** for `F64VEC`/`F32VEC` — e.g. `[rows:i32][cols:i32][rows*cols
   f... row-major]` (a header carrying `d,n`, mirroring the JVM packed matrix header `[2,d,n,...]`).
   Decide whether this is a *distinct* Ty or the same `F64VEC`/`F32VEC` with a rank tag; keep the
   rank-1 vector layout byte-identical.
2. **Minimal rank-2 support the GEMV needs**: `make-array (list d n) :element-type ...` builds the
   rank-2 block (relax `requireRank1Dims` for the packed-float case), `(aref W i j)` / `(setf (aref
   W i j) v)` do rank-2 row-major indexing, `array-dimensions` returns `(d n)`. Rank-2 `#d`/`#f`
   LITERALS can STAY a compile error initially (a GEMV `W` is usually built via `make-array` + a
   fill loop; literals are a later nicety).
3. **The GEMV kernel** (`compileSimdMatvec`): the per-row dot — reuse the existing `f64x2` /
   `f32x4` dot lane loop (`compileSimdDot` shape: `openSimdLoop` + horizontal add + scalar/remainder
   tail) run once per row of `W`, storing `y[i]` into a fresh rank-1 result vector of length `d`.
   Width-dispatch like the other kernels (`packedVecType(W)` -> f64x2 / f32x4). Remove `VEC_MATVEC`
   from `SIMD_UNSUPPORTED_NO_GC`; wire `typeOfSimd` (result = a rank-1 vector following `x`'s width)
   and `compileSimd`.
4. **Keep the API `(vec:matvec W x)`** (W rank-2), matching interpreter/JVM/wasm-GC — do NOT add a
   `(vec:matvec Wflat x d n)` explicit-dims form (it would diverge the surface). So `W` must be a
   `--no-gc` rank-2 packed array (from step 2).

## Scope / decisions to make

- **How big?** Real rank-2 packed arrays on the scalar backend is the foundational part; matvec is
  then a small kernel on top. Bound the rank-2 work to what GEMV needs (2-D make-array/aref/aset/
  array-dimensions for packed float only), NOT general rank-n / general-array rank-2.
- f32 GEMV computes entirely in f32 (matching the existing `F32VEC` kernels + llama2.c); f64 in
  f64. Cross-backend f32 print divergence => tests use integer/power-of-two values.
- Width-following: result follows `x` (like `vec::%make-like` / `JvmSimdVectorTemplate.simdMatvec`).

## Verify

- `WasmLispCompilerIntegrationTest` (Docker wasmtime `--invoke`, no `-W gc`): build a rank-2 `W`
  via `(make-array (list d n) :element-type 'double-float|'single-float)` + a fill loop, then
  `(vec:matvec W x)`; check the GEMV result (a `truncate`d reduction of it) for BOTH widths, over
  a `d`/`n` that exercises the `f64x2` pair + tail and the `f32x4` quad + remainder loop. Mirror
  `noGcRuns{Double,Single}FloatVecKernelsWith{F64x2,F32x4}Simd`.
- `NoGcWasmCompilerTest` (structural): the rank-2 layout + the `0xFD` `f64x2`/`f32x4` dot opcodes
  appear in the matvec kernel.
- (ci-spec does not run `--no-gc`, so no ci-spec case; the JVM `--simd` GEMV is already pinned by
  `vec-kernels-cross-backend` + `JvmSimdAccelCompilerTest`.)
- Update `.kb/vec.md` ("Acceleration layer 2" + drop the "Not done: --no-gc native f32x4 GEMV"
  bullet), the `vec:matvec` note ("`--no-gc` compile error" caveat), and
  `doc/{en,ja}/guides/simd-acceleration.md`.
- Native `CiSpecE2eTest` (no matvec `--no-gc` case, but re-run after any cross-backend touch).

## Pointers

- Reject site: `NoGcWasmCompiler.SIMD_UNSUPPORTED_NO_GC` (~L2434), `requireKnownSimd` (~L2456).
- Rank-1 guards to relax for packed float: `requireRank1Dims` (~L2342), `compileFloatArrayLiteral`
  (~L2157), the aref/aset subscript-count checks.
- Layout helpers: `allocVec`/`emitElementAddr`/`elemShift`/`compileMakeArray`/`compileSimdConstruct`
  (the new `constructorVecType` pattern), the `f64x2`/`f32x4` dot kernel `compileSimdDot` +
  `openSimdLoop`/`emitHorizontalAdd*`.
- JVM reference: `JvmSimdVectorTemplate.simdMatvec` (f64) / `matvecF` (f32) — rank-2 header
  `[2,d,n,...]`, `ow = 1 + (int)W[0] = 3`, per-row `simdDot` into a length-`d` result.
- Scalar reference (the oracle): `vec.lisp` `vec:matvec` (reads `(aref w i j)` over
  `array-dimensions`, allocates via `vec::%make-like`).
- Design context: `.kb/vec.md` "Acceleration layer 2 — --no-gc native v128" + "Not done"
  (`--no-gc native f32x4 GEMV`); todo-95 Part 2 (the JVM GEMV that landed); `.todo/98` (the
  at-scale demo that would exercise this).
