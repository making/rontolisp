# 95 — single-float (f32) support + `simd:matmul` (GEMV)

Goal: implement a llama2.c-style transformer in rontolisp + simd (à la kishida's
[Llama.java](https://gist.github.com/kishida/05656bfcbe840f269784f7dbbee5928e)). That needs
(1) **f32 packed arrays** (memory ½, SIMD lanes 2×) and (2) a **matmul/GEMV SIMD kernel**.
This doc covers BOTH, as two sequenced Parts. Builds on `.todo/94` (the packed *double*
float-array type, DONE + committed on `feat/packed-float-array`).

## Approved decisions (2026-07-08, user sign-off — do NOT re-litigate)

1. **`#f(...)` = single-float packed (f32); `#d(...)` = double-float packed (f64).**
   BREAKING: `#f` currently means *double* (todo 94 Option B) — it **flips to single**.
   linalg's *behavior* is unaffected (it uses `:element-type 'double-float`); only its
   PRINT form changes `#f(...)` → `#d(...)`.
2. **Scalars stay f64 (`LispDouble`). NO single-float scalar type.** Array element read
   **widens** f32→f64; write **narrows** f64→f32 (numpy-like). This deliberately DEFERS the
   full CL single/double scalar tower (the expensive, precision-risky part — new scalar
   type, float contagion rules, reader exponent markers `e`/`f`/`s`/`d`/`l`,
   `*read-default-float-format*`, a silent `1.0`→single precision flip). That is a SEPARATE
   future step ("Option C"), only if single-float *scalar arithmetic* is ever needed.
   **llama2 does not need it** — the perf win lives entirely in (a) `float[]` storage and
   (b) the `FloatVector` kernel, NOT in the scalar float type. f64 glue is even numerically
   nicer.
3. **Two packed array element-types, typed by CL `element-type`:** `single-float` /
   `double-float`. Primary API = `(make-array dims :element-type 'single-float | 'double-float)`
   (no new reader syntax needed; the `#f`/`#d` literals are shorthand).
4. **simd = width-polymorphic** (works on both; result = input width; mixed operands =
   error). **linalg = accepts single inputs (free, via aref-widening) but ALWAYS produces
   double** — with a forward-compat funnel so single-production is a one-touch add later.

## Why f32 (the llama2 driver)

- kishida Llama.java + llama2.c are **f32 throughout** (`FloatVector`, `float[]`); `matmul`
  is **GEMV** (`W(d,n)·x → xout(d,)`), FMA-accumulate + `reduceLanes`; **no quantization**
  (verified 2026-07-08 by fetching the gist).
- f32 vs our current f64: **memory ½** (stories15M 60MB vs 120MB; a 1.1B model 4.4GB vs
  8.8GB), **SIMD lanes 2×** (AVX2 256-bit = 8×f32 vs 4×f64; WASM v128 = f32x4 vs f64x2).

## The type (frontend)

Umbrella sealed interface + two records:

```
LispVal (seal)
 └ LispFloatArray                                       // sealed interface — "packed float array, some width"
    ├ LispDoubleFloatArray(double[] data, int[] dims)   // RENAMED from today's LispFloatArray
    └ LispSingleFloatArray(float[]  data, int[] dims)   // NEW
```

Interface exposes: `dims()`, `elementType()` (symbol `single-float`/`double-float`),
`double elementAt(int flat)` (widen on read), `setElement(int flat, double v)`
(narrow/store on write), plus typed backing (`double[]`/`float[]`) for the SIMD bridge.

**Rationale for the umbrella:** ~80-90% of dispatch (print layout, dims, rank,
array-total-size, length, arrayp, vectorp, coerce→list — and even `aref`-read, which
returns a `LispDouble` either way after widening) is **width-agnostic** → one
`case LispFloatArray` branch. Only `make-array`, `element-type`, narrow-write, and the SIMD
backing access are width-specific. The umbrella prevents duplicating the print/dims/length
logic twice per backend. (The umbrella is a frontend/AST concept; each backend still
dispatches on its concrete repr — `double[]`/`float[]`, `TYPE_FARRAY`/`TYPE_SFARRAY`,
`F64VEC`/`F32VEC`.)

## Reader / printer

- `#f(...)` → `LispSingleFloatArray`, `#d(...)` → `LispDoubleFloatArray` (rank-n, numpy-style
  inference — SAME parser as today's `#f`, only the target type differs). Parameterize the
  Option B `#f(` prefix logic by element-type (`#f(` for single, `#d(` for double).
- Print: single → `#f(...)`, double → `#d(...)`; the data bytes after the opening `(` stay
  byte-identical to the general array syntax.
- **Round-trip preserved** (the Option B property): reading either printed form rebuilds the
  same packed type — no degrade to a general array.

## Per-backend representation (rename existing → double, add single)

| layer | existing → double | new (single) |
| --- | --- | --- |
| frontend | `LispFloatArray` record → `LispDoubleFloatArray` + umbrella IF `LispFloatArray` | `LispSingleFloatArray` (`float[]`) |
| JVM repr | bare `double[]` `[rank,dims,data]` (Option-X header) | bare `float[]`, SAME header; discriminate `instanceof double[]` / `float[]` (both disjoint from `Object[]` cons/funref) |
| JVM helper | `JvmFloatArrayRuntimeBuilder` `_fv*` (double) | same builder, add `_sfv*` (single) |
| JVM SIMD | `JvmSimdVectorTemplate` `DoubleVector` | add `FloatVector` kernels |
| wasm-GC | `TYPE_FARRAY`/`TYPE_F64ARR` | `TYPE_SFARRAY`/`TYPE_F32ARR = array (mut f32)`; add `ref.test $sfarray` at every discriminator + the print prologue |
| --no-gc | `F64VEC` | `F32VEC`; v128 `f32x4` = 4 lanes (2× `f64x2`) |
| reader/printer | `#d(...)` = double | `#f(...)` = single |

JVM `float[]` header: rank/dims stored as `float` — exact for dims < 2^24 (>> any realistic
array dim; largest llama tensor is ~32000). Keeps the Option-X "bare array, zero-copy,
disjoint from `Object[]`" property.

## Package semantics

### simd = width-polymorphic

`add`/`sub`/`mul`/`scale`/`dot`/`sum`(/`matmul`) inspect the backing array →
`float[]`→`FloatVector`, `double[]`→`DoubleVector`. **Result = input width.** Operands must
share width (**mixed = error** — simd is the fixed-contract, no-fallback package). The bridge
(`JvmSimdVectorTemplate`) gains `FloatVector` kernels alongside `DoubleVector`; the call site
dispatches on the runtime repr. `simd.lisp` constructors (`zeros`/`ones`/`arange`) default
**double** + optional `:element-type` (keeps existing tests green). `--no-gc`: `f32x4`
kernels alongside `f64x2`.

### linalg = accepts both, produces double (forward-compat for later 産み分け)

- **Accepts single inputs FOR FREE:** linalg.lisp reads via `aref`/`row-major-aref`, which
  widen f32→f64 → **no code change** to consume `#f` inputs. `(linalg:add #f(..) #f(..))`
  works, computing in f64, returning `#d(..)`.
- **Produces double always.** FORWARD-COMPAT (user requirement): funnel EVERY linalg
  result-array allocation through ONE internal helper
  `linalg::%la-make(dims init &optional element-type)` (introduced NOW, always passes
  `'double-float`). Later "produce single too" = thread a literal `:element-type` through the
  public constructors + that one helper — localized, not 33-function churn.
- **COMPILE-PATH CONSTRAINT** (why it must be an explicit `:element-type` *literal*, NOT
  runtime input-type inference): the compiled backends pick `double[]`/`float[]` repr from a
  COMPILE-TIME LITERAL element-type. A runtime-computed element-type can't select the packed
  repr statically. So future linalg 産み分け = explicit `:element-type` literal at the call
  site (compilable), NOT automatic input-type promotion (would not compile).
- The `FloatVector` kernels built in Part 2 are reusable if/when linalg produces single.

## Part 1 — single-float support (foundation; independently shippable)

Phases mirror `.todo/94`'s order:

1. **Frontend:** umbrella `LispFloatArray` IF + rename `LispDoubleFloatArray` + new
   `LispSingleFloatArray`; reader `#f`→single / `#d`→double; printer; `equal`; resolver.
   Interpreter `aref`/`aset` (widen/narrow) / `length` / `make-array :element-type
   'single-float` / `array-element-type` / print. Introduce `linalg::%la-make` (still
   double). Green `LispEvaluatorTest` + `LispFloatArrayTest` (double + single).
2. **JVM:** `float[]` repr + `_sfv*` dispatch; `make-array` single-float; print `#f`/`#d`.
   Green `JvmLispCompilerTest` + `JvmFloatArrayTest`.
3. **JVM SIMD polymorphism:** `FloatVector` kernels in `JvmSimdVectorTemplate`; call-site
   dispatch on repr. `simd:dot`/`add`/... work in f32. Retain the dead-flag proof.
4. **wasm-GC:** `TYPE_SFARRAY`/`TYPE_F32ARR` + dispatch. wasmtime integration.
5. **`--no-gc`:** `F32VEC` + `f32x4` simd kernels. wasmtime `--invoke`.
6. **Reconcile the `#f`→`#d` print flip** across tests/docs/examples/ci-spec (the todo-94
   Option B / Phase 5b playbook — same `#(`/`#nA(`→prefix churn, now double RESULTS print
   `#d`; add single-float cases). `data-types.md` `#f`/`#d` doc (en+ja). Native
   `CiSpecE2eTest` + all-4-backend verify.

**Done-line:** f32 & f64 packed arrays run byte-identical across backends; `#f`/`#d`
round-trip; simd ops width-polymorphic; linalg behavior unchanged (prints `#d`).

## Part 2 — `simd:matmul` (GEMV) (the llama2 payoff; depends on Part 1's f32 path)

- Add `simd:matmul` — really **GEMV** (matrix `W(d,n)` × vector `x(n,)` → `xout(d,)`), which
  is llama2's actual op (every projection/FFN/classifier). GEMV is the **CLEAN** case: `W`
  rows are contiguous, `x` is contiguous → **no transpose**; the inner loop is a vectorized
  dot (FMA-accumulate + `reduceLanes`) = `JvmSimdVectorTemplate.dot` run `d` times in ONE
  bridge call (amortizes bridge entry over the whole matrix vs a `d`-times `simd:dot` loop).
- **Both widths:** f32 (`FloatVector`, llama2) + f64 (`DoubleVector`). The f64 GEMV can land
  first (independent of Part 1); the f32 GEMV needs Part 1.
- mat×vec is all llama2's single-token decode needs; full mat×mat **GEMM** (strided `B`
  column → transpose-`B`) is NOT required — defer/skip unless prefill batching is wanted.
- Gate on `--simd` + `programUsesAnyAcceleratedSimdOp` (add `matmul` to the walk).
  Byte-identical vs the scalar `simd.lisp` fallback (`JvmSimdAccelCompilerTest`-style) +
  dead-flag proof. Determinism: SIMD reduction associativity → last-ULP; for ci-spec /
  cross-backend use inputs whose float results are exact (power-of-two / integer-valued).
- **Files:** extend `JvmSimdVectorTemplate` (matmul f32+f64), `JvmSimd{Compiler,
  RuntimeBuilder}`, `LispNames`/`PackageRegistry.SIMD_FUNCTIONS` (`simd:matmul`), `simd.lisp`
  scalar fallback; `ScalarWasmCompiler` `f32x4` GEMV on `--no-gc` optional.

**Done-line:** `simd:matmul` accelerated f32+f64, byte-identical, dead-flag-proven; a
stories15M-scale llama2 demo runs.

## Risks / must-verify

- **Precision regression AVOIDED by construction** — scalars stay f64; only array *storage*
  is f32; no `1.0`→single flip, no arithmetic contagion, no reader exponent markers.
- **WASM float32 print divergence:** f32 non-terminating decimals diverge across backends
  worse than f64 → cross-backend tests/docs/examples use integer-valued / power-of-two f32
  values (the Phase 5b idiom, tightened for f32).
- **JVM `float[]` header exactness:** rank/dims as `float` exact for dims < 2^24 (>> any real
  array).
- **Memory:** a real 1.1B model as f32 = 4.4GB — feasible only at small scale
  (stories15M/110M). Note it; don't chase large models. (And our arrays are still boxed at
  the Lisp API boundary; the win is storage + kernel, not a zero-overhead runtime.)

## Pointers

- **Reuse the entire todo-94 packed-*double* machinery** — mirror it for `float[]`. SIMD
  bridge: `JvmSimdVectorTemplate` (add `FloatVector`). Reader/printer: `LispArray.
  renderArrayData` + the Option B `#f(` prefix (parameterize by element-type).
- **Reconciliation playbook:** `.todo/94` "Option B DONE" + "Phase 5b DONE" (same doc/test/
  ci-spec churn, now `#f`↔`#d`; a `pack_fprint.py`-style scripted rewrite).
- **Example target:** a new `examples/ml/llama2*.lisp` (stories15M) once Part 2 lands.
- Model refs: kishida Llama.java (f32, `FloatVector`, GEMV, FMA + `reduceLanes`, no quant);
  Karpathy llama2.c `matmul(xout, x, w, n, d)` = GEMV.
