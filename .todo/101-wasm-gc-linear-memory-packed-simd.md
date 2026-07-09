# 101 — wasm-GC honors `--simd` too: back packed float arrays with linear memory → v128 on all 4 backends

**Goal:** close the one gap that `.todo/100` (案2) leaves open — wasm-GC cannot do v128 — so that
`--simd` is honored **uniformly on every compiled backend**. This is proposal "案3" from the
2026-07-09 design discussion; it is the true endpoint where the "one knob" is fully uniform.

**Do `.todo/100` first.** This builds on it with no rework: everything 案2 ships (the `--simd`
threading, the `--no-gc` scalar path, the v128 kernels gated by `--simd`) survives. 案3 is
"additive + one factoring": reuse 案2's kernel seam from the GC backend, plus the genuinely new and
heavier piece below.

## The blocker 案2 leaves open

WASM `v128.load`/`store` address **linear memory**. wasm-GC packed float arrays are GC objects
(`TYPE_FARRAY` over `TYPE_F64ARR`/`TYPE_F32ARR`), reached by `array.get`/`array.set`, with no
linear-memory address — so there is no `v128.load` from them. To SIMD a wasm-GC packed vector you
must either copy it into linear memory per op (the copy eats the win) or **store the packed vector
in linear memory to begin with**. This todo does the latter.

## Where 案3's cost actually is (honest scope)

The cost is NOT in anything 案2 leaves behind — it is concentrated in the wasm-GC packed-array
**representation change**:

1. **Kernel extraction (clean, 案2-shaped)**: `NoGcWasmCompiler` and `WasmLispCompiler` are
   separate classes. Lift the v128 kernel emission (the seam 案2 was asked to carve — "over a
   linear-memory block base+count+width, emit a {scalar|v128} loop") into a shared helper both call.
   If 案2 built the seam well this is mechanical.
2. **Linear-memory-backed packed arrays on wasm-GC (the new, heavy part)**: give the wasm-GC packed
   float vector/matrix type a linear-memory representation. The GC module already has a linear
   memory (the `HEAP_PTR` intern/scratch region — see `.kb/wasm-gc-strings.md`), so the block can
   live there. **Design fork to decide:**
   - **Single hybrid repr** — the packed-float type is *always* linear-memory-backed on wasm-GC
     (cons/string/general arrays stay GC). Cleanest conceptually, but the generic array runtime
     (`aref`/`length`/`array-dimensions`/`array-element-type`, and printing) must branch on
     "linear-memory packed vs GC array" everywhere a packed array can appear. Invasive but uniform.
   - **Dual repr gated by `--simd`** — only under `--simd` does `#d(...)`/`make-array
     :element-type` produce a linear-memory packed array on wasm-GC. Smaller blast radius, but
     `--simd` then changes the *representation* of a value, which can leak into identity/printing/
     interop with non-vec code. More footguns.
   Recommend evaluating the single hybrid repr first (uniformity beats a flag-dependent value repr).

## Payoff beyond uniform `--simd`

Once packed arrays are linear-memory-backed in **both** WASM modes (wasm-GC-simd and `--no-gc`),
the rank-2 packed layout + GEMV kernel needed by `.todo/99` (`--no-gc` `vec:matvec`) can be
**shared** between the two instead of built twice. `.todo/101` and `.todo/99` should be sequenced
together (or 99 folded into 101's layout work) so the rank-2 linear-memory layout + the per-row
`f64x2`/`f32x4` dot kernel serve both backends. That also unlocks the `.todo/98` at-scale demo on
wasm-GC (not just `--no-gc`).

## Target state

| target | `--simd` |
|---|---|
| JVM | Vector API (unchanged) |
| wasm-GC | **v128** (NEW — was scalar+warn in 案2) |
| wasm `--no-gc` | v128 (unchanged) |

wasm-GC + `--simd` flips from 案2's "warn + scalar `vec.lisp`" to real v128, byte-identical to the
scalar reference — a pure capability upgrade, no compatibility break (案2's warn-path callers just
get faster output).

## Verify

- `WasmLispCompilerIntegrationTest` (Docker, `-W gc`, wasm-GC + `--simd`): vec kernels produce
  byte-identical results to the scalar wasm-GC path (no `--simd`) and to the `--no-gc --simd` path,
  over both `#d` and `#f`; the module contains `0xFD` v128 opcodes AND a linear memory.
- Regression: wasm-GC WITHOUT `--simd` still uses scalar `vec.lisp` and is unchanged; `--no-gc`
  (both scalar and `--simd`) unchanged; JVM `--simd` unchanged.
- If folded with `.todo/99`: the rank-2 `vec:matvec` GEMV runs on wasm-GC-simd and `--no-gc --simd`
  from the shared layout.
- Native `CiSpecE2eTest` after any cross-backend output touch.

## Pointers

- wasm-GC backend: `WasmLispCompiler`; current packed repr `TYPE_FARRAY`/`TYPE_F64ARR`/`TYPE_F32ARR`
  and the generic array runtime (`aref`/`length`/`array-dimensions`/`array-element-type`); the
  existing GC-module linear memory (`HEAP_PTR`, `.kb/wasm-gc-strings.md`).
- Shared kernel seam + v128 kernels: `NoGcWasmCompiler` `compileSimd`/`compileSimd*`/`openSimdLoop`/
  `allocVec`/`elemShift` (the seam `.todo/100` carves out).
- Rank-2 / GEMV: `.todo/99` (the `--no-gc` rank-2 matvec, to share a layout with),
  `JvmSimdVectorTemplate.simdMatvec`/`matvecF` (the JVM GEMV reference).
- Docs/kb: `doc/{en,ja}/guides/simd-acceleration.md` (wasm-GC row → v128), `.kb/vec.md`,
  `.kb/no-gc-scalar-wasm.md`, `.kb/wasm-gc-strings.md` (the linear-memory region).
- Design context: `.todo/100` (案2, prerequisite), `.todo/98` (at-scale demo), the
  "single-float-and-matmul-plan" memory (the lineage).
