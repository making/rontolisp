# 95 — single-float (f32) support + `vec:matvec` (GEMV)

Goal: implement a llama2.c-style transformer in rontolisp + the packed-vector package (à la
kishida's [Llama.java](https://gist.github.com/kishida/05656bfcbe840f269784f7dbbee5928e)).
That needs (1) **f32 packed arrays** (memory ½, SIMD lanes 2×) and (2) a **matvec/GEMV SIMD
kernel**. This doc covers BOTH, as two sequenced Parts. Builds on `.todo/94` (the packed
*double* float-array type, DONE + committed on `feat/packed-float-array`).

> **NAMING (decided 2026-07-08, see `.todo/96`):** the `simd:` package is being renamed
> **`vec:`** (name the abstraction, not the optional impl -- Java Vector API / numpy
> precedent), and the Part 2 op is **`vec:matvec`** (GEMV), NOT `simd:matmul`. The `--simd`
> flag STAYS (it names the real SIMD acceleration), and the `JvmSimd*` / `ScalarWasmCompiler`
> `v128` SIMD internals KEEP their `Simd` names. **Do the `.todo/96` rename BEFORE Phase 4**
> (cheapest before more `simd:` refs land). The already-DONE Phase 1-3 notes below still say
> `simd:` (historical); read them as `vec:`.

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
4. **simd (→ `vec:`) = width-polymorphic** (works on both; result = input width; mixed
   operands = error). **linalg = accepts single inputs (free, via aref-widening) but ALWAYS
   produces double** — with a forward-compat funnel so single-production is a one-touch add
   later.
5. **Package `simd:` → `vec:` (rename; full task in `.todo/96`, do it BEFORE Phase 4).** The
   package is a portable packed *vector* abstraction, SIMD-accelerated only under `--simd` /
   `--no-gc` (scalar elsewhere), so it is named for the abstraction; `--simd` stays as the
   acceleration flag and the `JvmSimd*` / `ScalarWasmCompiler` SIMD internals keep their
   names. **`vec:` membership rule** = the RESULT is a vector or scalar: `add`/`sub`/`mul`/
   `scale` → vector, `dot`/`sum`/`mean`/`norm` → scalar, **`matvec` (GEMV) → vector**.
   Matrix-PRODUCING ops (`matmul`/GEMM, `outer`, `transpose`, `reshape`) stay in `linalg:`;
   the Part 2 op is **`vec:matvec`** (matrix×vector→vector), NOT `matmul`. **linalg is a
   future accel *client*, not part of this rename:** it stays rank-n / always-double /
   currently-scalar, but can LATER be sped up by the SAME `--simd`/`--no-gc` switch if its
   inner loops route through `vec:` kernels (the `linalg::%la-make` funnel is the seam; GEMM
   needs a transpose that GEMV avoids, and is best routed through a batched `vec:` kernel to
   amortize bridge entry).

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

1. **[DONE 2026-07-08]** **Frontend:** umbrella `LispFloatArray` IF + rename
   `LispDoubleFloatArray` + new `LispSingleFloatArray`; reader `#f`→single / `#d`→double;
   printer; `equal`; resolver. Interpreter `aref`/`aset` (widen/narrow) / `length` /
   `make-array :element-type 'single-float` / `array-element-type` / print. Introduce
   `linalg::%la-make` (still double). Green `LispEvaluatorTest` (711) + `LispFloatArrayTest`
   (21, double + single).
   - Sealed hierarchy: `LispVal` → `LispFloatArray` (sealed IF, `dims`/`elementType`/
     `elementAt` widen / `setElement` narrow / `openPrefix` / `toGeneralArray` + default
     `rank`/`totalSize`/`readFlat`/`aref`/`aset`/`flatIndex`/`print`/`display`) →
     `LispDoubleFloatArray(double[])` / `LispSingleFloatArray(float[])`.
   - Reader: `Token.FloatArrayOpen(boolean single)`; lexer `#f(`→single, `#d(`→double
     (no `#d` collision); `LispReader.readFloatArray(single)` narrows leaves to f32 for
     `#f`. Leaf-error message de-hardcoded to "packed float array: expected a number".
   - Codegen kept COMPILING (double path renamed to `LispDoubleFloatArray`); a
     `LispSingleFloatArray` literal or `make-array :element-type 'single-float` throws a
     clear compile error on JVM/WASM/`--no-gc` (no todo ref in the message). Umbrella
     `instanceof LispFloatArray` gates unchanged.
   - **`#f`→`#d` reconciliation PULLED FORWARD** (Phase 6's mechanical core; user chose
     "green the build now"): the 48 initially-red tests were all *double* tests still
     written with `#f`. Rewrote `#f(`→`#d(` in the 5 compiler test files + `ci-spec.yaml`
     (which the two shaker corpus tests read) + all docs; flipped the compiled-backend
     print prefix (`JvmLispCompiler` PackedPrint regex-replacement + `WasmLispCompiler`
     `StringTable.fPrefix` + the `--no-gc` rank-2 error message) from `#f(` to `#d(`;
     rewrote `data-types.md` (en+ja) prose to define `#d`=double / `#f`=single. **Full
     `./mvnw test` GREEN (2913, 0 fail), web-profile compile GREEN, javadoc clean (only the
     sanctioned Version error), native `CiSpecE2eTest` GREEN (724).** Remaining for Phase 6:
     add single-float cross-backend `ci-spec` cases + single-float doc examples once the
     backends support single (Phases 2-5).
2. **[DONE 2026-07-08]** **JVM:** `float[]` repr + `_sfv*` dispatch; `make-array`
   single-float; print `#f`/`#d`. Green `JvmLispCompilerTest` + `JvmFloatArrayTest`.
   - **Repr:** a bare `float[]` with the SAME header layout as the packed `double[]`
     (`[rank, dims..., data...]`, rank/dims stored as f32, exact for realistic dims); a
     `float[]` is disjoint from `Object[]`/`ArrayList`/`double[]`, so no discriminator
     churn.
   - **Accessors handle BOTH widths inline, not separate `_sfv*` accessors** (the design
     doc's `_sfv*` was a sketch): a call site like `(aref x i)` emits ONE helper call and
     can't know the width statically, so each `_fv*` accessor (`_fvAref1/2/N`,
     `_fvAset1/2/N`, `_fvDims`, `_fvLength`, `_fvToGeneral`, `_fvElementType`) now
     dispatches `instanceof double[]` -> `instanceof float[]` -> general-delegate. Reads
     widen f32->f64 (`faload;f2d`), writes narrow (`d2f;fastore`), aset returns the
     read-back narrowed value (`d2f;f2d`). Factored via width helpers
     (`loadElem`/`loadHeaderInt`/`storeElem`/`storeHeaderInt`/`newBacking`) so the double
     branch stays byte-identical.
   - **Allocation IS width-specific** (compile-time literal element-type picks it):
     `_fvMake` (double) + new `_sfvMake` (float), one `buildMake(single)` parameterized.
     `JvmArrayCompiler` routes `:element-type 'single-float` -> `_sfvMake`.
   - **Literal:** `JvmQuoteCompiler.compileSinglePackedLiteral` emits a `float[]`; each
     element float is stored as its widening `double` constant narrowed with `d2f` (exact
     round-trip) so NO float constant-pool entry is needed. Wired in `JvmExprCompiler` +
     `JvmQuoteCompiler` (both throws removed).
   - **Print:** `PackedPrint` record + `emitArrayBranch` gained a `float[]` branch (`#f(`
     prefix) alongside `double[]` (`#d(`), both via the shared `_fvToGeneral` (widens);
     factored into `emitPackedPrintBranch`. `usesFloatArray` gate + `makeArrayIsPackedFloat`
     now also detect `:element-type 'single-float`. `JvmAsm` gained
     `newarrayFloat`/`faload`/`fastore`/`f2d`/`d2f`/`f2i`/`i2f`. `JvmArraypCompiler` tests
     `float[]` too.
   - **Verified:** `./mvnw test` GREEN (2930, 0 fail), `JvmFloatArrayTest` 33 (12 new
     single-float parity tests), web-profile compile SUCCESS, javadoc clean (only Version
     exception). 4-backend CLI check: interpreter + JVM BYTE-IDENTICAL for `#f`
     (round-trip, widen-on-read `2.5`, narrow-on-write `0.10000000149011612`,
     `array-element-type` -> `single-float`, `#d` unchanged); WASM P1 + `--no-gc` still
     clean compile errors (Phases 4/5). Native `CiSpecE2eTest` NOT re-run: `ci-spec.yaml`
     untouched + no cross-backend output change (single-float is JVM-only new capability,
     not in ci-spec; double output byte-identical).
3. **[DONE 2026-07-08]** **JVM SIMD polymorphism:** `FloatVector` kernels in
   `JvmSimdVectorTemplate`; call-site dispatch on repr. `simd:dot`/`add`/... work in f32.
   Retain the dead-flag proof.
   - **Bridge (`JvmSimdVectorTemplate`) is width-polymorphic:** each kernel dispatches on
     the runtime backing type -- `a instanceof float[]` runs the `FloatVector` path, a
     `double[]` the original `DoubleVector` path (kept BYTE-IDENTICAL; the float check is a
     guard prepended above the untouched double body). Element-wise (`add`/`sub`/`mul`) on
     `float[]` -> a fresh `float[]` (width preserved); reductions (`sum`/`dot`) -> the usual
     f64 scalar. **Mixed single/double operands = a hard `IllegalArgumentException`** (simd
     is the fixed-contract, no-fallback package; message has no todo ref).
   - **Precision (matches the `#f`/`#d` model, scalars stay f64):** `add`/`sub`/`mul` run in
     NATIVE f32 -- bit-identical to the scalar reference's widen-to-f64/compute/narrow
     because a single `+`/`-`/`*` of two floats has no double-rounding. `scale` multiplies by
     the genuine f64 scalar in f64 then narrows (a scalar loop; native-f32 would diverge for
     a non-f32-exact scalar). `sum`/`dot` accumulate in f64: each f32 lane is widened via
     `FloatVector.convert(F2D, 0/1)` into a `DoubleVector` accumulator (a preferred float
     vector holds 2x the lanes of a preferred double vector), matching the scalar oracle;
     only reduction associativity differs, so tests use f64-exact integer inputs.
   - **`simd.lisp` made width-preserving** so the scalar reference (and hence `--simd`)
     stays byte-identical: new `simd::%make-like` allocates a fresh vector whose element
     type matches the prototype (`(if (eq (array-element-type p) 'single-float) (make-array
     ... 'single-float) (make-array ... 'double-float))` -- both branches literal so the
     repr is picked statically); `%map2` (add/sub/mul) + `scale` route through it. Reductions
     / constructors unchanged (constructors still default double).
   - **WASM ordering gotcha + fix:** WASM-GC can't compile the `'single-float` make-array
     (Phase 4 pending) yet splices `simd.lisp`; `--no-gc` doesn't splice it at all. The
     shared `SimdLibrary.forms()` is cached once with INTERPRETER features, so a bare
     `#+`/`#-` reader conditional could NOT distinguish the target. Fixed by making
     `SimdLibrary.forms(Features)` / `process(program, Features)` read `simd.lisp` with the
     TARGET's feature set (cached per wasm-or-not); `simd::%make-like` then has a
     `#-rontolisp-wasm` width-preserving variant and a `#+rontolisp-wasm` double-only
     variant. Only the WASM callers pass `Features.WASM` (`RontoLispCli`, `RontoPlayground`
     `compileWasm`, `WasmTreeShakerCorpusTest`); the no-arg `process`/`forms` stay the
     non-wasm (width-preserving) default for interpreter + JVM. Deliberately did NOT let WASM
     `make-array 'single-float` degrade to double (that would silently diverge cross-backend
     for direct user use; the clean "not supported" error is kept).
   - **Verified:** `./mvnw test` GREEN (2937, 0 fail; +7 float simd parity/dead-flag tests in
     `JvmSimdAccelCompilerTest`, now 14), web-profile compile SUCCESS, javadoc clean (only
     Version). 4-backend CLI: interpreter + JVM(scalar) + JVM(`--simd`) BYTE-IDENTICAL for
     `#f` (`#f(4.0 6.0)` width-preserved, `sum`/`dot` f64 scalars, `mean`/`norm`); WASM-GC
     double simd runs under wasmtime (`#d(4.0 6.0)`, regression-free after the `%make-like`
     change) and `#f` is a clean compile error; `--no-gc` `#f` a clean error. Native
     `CiSpecE2eTest` NOT re-run (ci-spec untouched, no cross-backend output change; float
     simd is interpreter/JVM-only new).
4. **[DONE 2026-07-08]** **wasm-GC:** single-float packed arrays. wasmtime integration.
   - **Repr decision (deviates from the `TYPE_SFARRAY` sketch above, mirroring the JVM
     Phase-2 "one struct, dispatch on backing" lesson):** keep the SINGLE `TYPE_FARRAY`
     struct (`{(ref null eq) dims, (ref null eq) data}`, unchanged) and add only ONE new
     type `TYPE_F32ARR = array (mut f32)` (index 41, right after `TYPE_FARRAY`); the data
     field holds a `TYPE_F64ARR` (double) OR a `TYPE_F32ARR` (single), told apart by
     `ref.test $f32arr`. **Why not a second struct:** two structurally-identical
     singleton-struct rec groups would *canonicalize to the same type* under wasm-GC
     iso-recursive typing, so `ref.test` could not discriminate them; a distinct
     `array (mut f32)` element type is the clean discriminator. **Bonus:** the
     width-agnostic ops (`arrayp` / `length` / `array-dimensions`, which test `$farray`
     and read the `dims` field) need ZERO change — strictly fewer touch points than the
     two-struct sketch. Wrapper/import type indices shifted `TYPE_FARRAY + 1` ->
     `TYPE_F32ARR + 1` (`WasmLispCompiler`).
   - **Width-specific points (all in `WasmArrayCompiler` + `WasmQuoteCompiler` +
     `WasmRuntimeBuilder` print):** `#f` literal (`compileSinglePackedLiteral`: each f32 =
     its widening `f64.const` narrowed by `f32.demote_f64`, the exact-round-trip JVM `d2f`
     trick, so no f32 immediate encoding); `make-array :element-type 'single-float`
     (`compilePackedMake(single=true)`, allocs `TYPE_F32ARR`); `aref`/`row-major-aref`
     (`emitPackedReadF64`: `ref.test $f32arr` -> `array.get $f32arr` + `f64.promote_f32`,
     else f64); `%aset`/`%row-major-aset` (`emitPackedWriteF64`: narrow `f32.demote_f64`
     for single, and RETURN the value AS STORED read-back-widened — matching the
     interpreter/JVM aset return across widths); `array-element-type` (single-float vs
     double-float by data width); print (`_print_val`/`_princ_val` gained a `singleSlot`
     i32 local; `packedSlot` is now tri-state 0/1/2 selecting `#(` / `#d(` / `#f(`; the
     per-element boxing loop widens f32; length via an abstract-`array` cast so it is
     width-agnostic).
   - **Deliberately NOT changed:** `--no-gc` (`ScalarWasmCompiler`) still cleanly rejects
     single-float (its Phase-5 error) — verified. `vec.lisp` `#+rontolisp-wasm`
     double-only `%make-like` stays: `Features.WASM` is shared by wasm-GC AND `--no-gc`
     (no feature distinguishes them), so removing it would break `--no-gc` vec programs
     (the width-preserving variant statically compiles a `make-array 'single-float`
     branch `--no-gc` cannot). So `vec:` ops on `#f` inputs still produce double on
     wasm-GC (graceful; a narrow edge, revisit if a wasm-GC-only feature is ever added).
   - **Verified:** `./mvnw test` GREEN (2937/0, unchanged baseline — no regression) +
     10 NEW `WasmLispCompilerIntegrationTest` single-float cases (literal rank-1/2, aref
     widen, aset return, make-array 1-d/2-d, element-type, row-major, typecase array/
     vector, dot-product loop, coerce->list, arithmetic narrowing proof) GREEN; web-profile
     compile OK; javadoc clean (only Version). **All 4 backends BYTE-IDENTICAL** for exact
     (power-of-two / integer-valued) `#f` values (interp / JVM / WASM P1 / WASM component);
     the only cross-backend text diff is the PRE-EXISTING WASM double-printer rendering of
     non-terminating decimals (`(print 0.10000000149011612)` -> `0.1` on WASM even with no
     single-float involved), which is exactly why cross-backend `#f` tests use exact values
     + an arithmetic (`= ...`) narrowing proof. Native `CiSpecE2eTest` NOT re-run
     (`ci-spec.yaml` untouched; single-float wasm is new capability, not in ci-spec — its
     cross-backend cases land in Phase 6). `--no-gc`/`#f` = clean compile error (Phase 5).
5. **[DONE 2026-07-08]** **`--no-gc`:** `F32VEC` + `f32x4` simd kernels. wasmtime `--invoke`.
   - **Prereq DONE:** added `F32X4_SPLAT=0x13`, `F32X4_ADD=0xE4`/`SUB=0xE5`/`MUL=0xE6` to
     `am/ik/wasm/Instruction.java`. **CORRECTED the plan's `F32X4_EXTRACT_LANE`: it is
     `0x1F`, NOT `0x1B` (0x1B is `i32x4.extract_lane`; the existing `F64X2_EXTRACT_LANE=0x21`
     anchors the packed-by-element-type lane-op table).** No `writeF32` in `WasmWriter`, so
     every f32 const uses the `F64_CONST value; F32_DEMOTE_F64` exact-round-trip trick
     (`f32Const`).
   - **5a storage DONE:** `Ty.F32VEC` added (i32 ref kind in `valType`/`wasmType`/`coerce`
     via a new `isRefKind`; a f64-vector/f32-vector mismatch is a type error). `typeOf`
     splits `LispSingleFloatArray -> F32VEC` (before the umbrella `LispFloatArray -> F64VEC`
     case). `MAKE_ARRAY` `typeOf` + `compileMakeArray` key off `:element-type` via a new
     `isSingleFloatElementType`. `allocVec`/`emitElementAddr`/`compileFloatArrayLiteral`/
     `compileMakeArray` are width-parameterized through `elemShift(vecTy)` (f64 `<<3`, f32
     `<<2`); the F64VEC calls stay BYTE-IDENTICAL (2-arg `allocVec` delegates to F64VEC,
     shift const still 3). `compileAref`/`compileAset` pick the width with `packedVecType`
     (anything not statically F32VEC stays F64VEC, so every existing program is unchanged):
     f32 aref = `f32.load` + `f64.promote_f32`, f32 aset = `f32.demote_f64` + `f32.store`
     returning `promote(demote(x))`. `compileLength` guard accepts F32VEC.
   - **5b kernels DONE:** `typeOfSimd` returns the operand width for element-wise/`scale`
     (constructors stay F64VEC). Each of `compileSimdElementwise`/`Scale`/`Sum`/`Dot`
     early-branches `if (packedVecType(arg1)==F32VEC) return …F32(...)` so the f64x2 bodies
     are untouched/byte-identical. The f32 kernels (`compileSimd*F32`) use
     `openSimdLoop(…, laneShift=2)` (quads), `f32x4.*` ops (`f32x4Of`/`f32ScalarOf` map the
     dispatched f64 op), an `openScalarTailLoop(count & 3)` **remainder loop** (vs the f64
     single-if), `emitSplatZeroF32`/`emitHorizontalAddF32` (4-lane fold), and an f32 running
     sum in a raw `allocF32Local()` promoted to f64 on return. **Decision:** f32 kernels
     compute ENTIRELY in f32 (matches llama2.c / FloatVector; each `--no-gc` width computes
     in its own native precision), diverging from the f64 vec.lisp oracle only for
     non-f32-exact operands — so tests use exact values.
   - **Scope confirmed (parity, not more):** `array-element-type`/`array-dimensions` and
     packed-vector `wasm-export` marshaling still don't exist on `--no-gc` for EITHER width
     (no regression, no new surface). `vec.lisp` is not spliced on `--no-gc`, so the wasm-GC
     `%make-like` entanglement doesn't apply.
   - **WasmTreeShaker `0xFD` fix (pre-existing gap, surfaced by the new `--optimize` E2E):**
     the `--optimize` tree-shaker's `scanInstr` had no case for the `0xFD` SIMD prefix, so
     `--no-gc --optimize` on ANY `vec:` program (f64 OR f32) threw "unhandled opcode 0xFD".
     Added `skipSimd` (mirrors `skipGc`): reads the u32-LEB sub-opcode, skips a memarg for
     `v128.load`/`store` (0x00/0x0B), one lane byte for `f32x4`/`f64x2.extract_lane`
     (0x1F/0x21), nothing for splat + lane-wise arithmetic, throws on any other SIMD
     sub-opcode. (`v128`/`f32` value-type bytes 0x7B/0x7D were already handled by
     `skipValType`.)
   - **Verified:** `./mvnw test` GREEN (**2956/0**, +7 `ScalarWasmCompilerTest` single-float
     cases: `#f` literal storage narrow/widen opcodes, make-array 'single-float, rank-2 `#f`
     error, both-width make-array error, f32x4 reduction opcodes `0xFD 0x13`/`0x1F`/`0xE6`,
     f32x4 element-wise `0xFD 0x0B`/`0xE4`, mixed-width type error; +2 automated Docker
     wasmtime E2E). web compile untouched (`--no-gc` is not in `src/web/java`); javadoc clean
     (Version only). **Automated wasmtime E2E** (`WasmLispCompilerIntegrationTest`
     `noGcRuns{Double,Single}FloatVecKernelsWith{F64x2,F32x4}Simd`, `wasmtime run --invoke`,
     no `-W gc`, int-returning `truncate` wrappers): f32 `vec:dot`=55, `vec:sum`=280 (count 7
     = 1 quad + 3-elem remainder loop), `vec:add`+aref=33/11 (count 3 = pure tail, 0 quads),
     `vec:scale`+sum=45, make-array 'single-float + setf aref buildsum(5)=30/(8)=140, +
     `--optimize` re-run — ALL correct, matching the interpreter f64 oracle; the F64VEC `#d`
     path verified in the sibling test (same shapes, first automated wasmtime run of the
     `--no-gc` vec kernels — they were structural-only before). Native `CiSpecE2eTest` NOT
     re-run (`ci-spec.yaml` untouched; `--no-gc` is not a ci-spec backend and single-float
     `--no-gc` is new capability — Phase 6 adds any cross-backend `#f` cases).

   Original plan (as executed) below. **[PLAN — grounded
   in a full `ScalarWasmCompiler` map, 2026-07-08; line numbers are from `29dcbc6`.]**
   All edits are in `ScalarWasmCompiler.java` unless noted. F32VEC = an i32 pointer to
   linear memory `[count:i32 LE][count f32 LE]` (4-byte stride, half of F64VEC's 8-byte).
   Scalars stay f64 — reads widen `f64.promote_f32`, writes narrow `f32.demote_f64`.
   **Scope = PARITY with F64VEC, not more:** `array-element-type`/`array-dimensions` do NOT
   exist on `--no-gc` for EITHER width (they hit the unsupported-op throw ~line 3118), and
   packed-vector `wasm-export` marshaling does NOT exist for F64VEC either (only `:string`
   is a reference designator) — so both stay unsupported for F32VEC too (no regression, no
   new surface). `usesFloatArray` (799, umbrella-based) + `withLocals`/v128 locals need NO
   change. On `--no-gc` `vec.lisp` is NOT spliced (vec: is intercepted natively by
   `compileSimd`), so the wasm-GC `%make-like` entanglement does NOT apply here.

   **Prereq — add `F32X4_*` opcodes to `am/ik/wasm/Instruction.java`** (only `F64X2_*`
   exist today, ~lines 607-639): `F32X4_SPLAT=0x13`, `F32X4_EXTRACT_LANE=0x1B`,
   `F32X4_ADD=0xE4`, `F32X4_SUB=0xE5`, `F32X4_MUL=0xE6` (+ `DIV=0xE7`/`MIN=0xE8`/`MAX=0xE9`
   if used). `F64_PROMOTE_F32=0xBB`, `F32_DEMOTE_F64=0xB6`, `Type.F32=0x7D`, `Type.V128=0x7B`
   already exist. **Verify `WasmWriter.writeF32` exists** — if not, emit each f32 const as
   the widening `F64_CONST` + `F32_DEMOTE_F64` (the same trick used in Phase 4's
   `WasmQuoteCompiler`/`WasmArrayCompiler`).

   **5a — F32VEC storage (foundational; the `--no-gc` analog of Phase 4):**
   - `Ty` enum (~line 90): add `F32VEC`; add it to the `case STRING, F64VEC -> I32` arm of
     `valType()` (~138) AND the separate `wasmType(Ty)` switch (~1194). `join` needs no edit
     (auto mutually-exclusive).
   - `typeOf` line 470 `case LispFloatArray -> F64VEC` **is the bug**: split into
     `LispDoubleFloatArray -> F64VEC`, `LispSingleFloatArray -> F32VEC`.
   - `typeOfCall` MAKE_ARRAY (~576-581): inspect `:element-type` → F64VEC/F32VEC (single
     via an `isSingleFloatElementType` mirroring `isDoubleFloatElementType` ~2274). AREF/
     ASET result stays `FLOAT` (scalars are f64) — no change.
   - `compileFloatArrayLiteral` (2112): remove the `#f` throw (2113-2118); branch on
     `LispSingleFloatArray` → `float[] data()`, offset `4 + 4*i`, `F32_CONST`(or demote
     trick)+`F32_STORE`. `compileMakeArray` (2176): remove the single-float rejection
     (2180-2183); branch stride `<<3`→`<<2`, `F32_CONST 0.0` default, `F32_DEMOTE_F64` the
     `:initial-element` before `F32_STORE`.
   - `allocVec` (2077): stride `<<3`(const 3)→`<<2`(const 2). `emitElementAddr` (2095): same.
     BOTH must become width-aware (take the element width, or add `allocVecF32`/
     `emitElementAddrF32`).
   - `compileAref` (2146): F32VEC → `F32_LOAD` + `F64_PROMOTE_F32`. `compileAset` (2155):
     F32VEC → `F32_DEMOTE_F64` + `F32_STORE`, return the round-tripped f64 (match interp/JVM
     aset return). **Both must branch on the operand's inferred `Ty`** (they hardcode f64
     today — thread the width from `typeOf(vec)`). `compileLength` guard (~2049): add
     `F32VEC` (layout-agnostic, just the type guard).

   **5b — f32x4 `vec:` kernels (the SIMD payoff; branch each kernel on operand `Ty`):**
   - Shared helpers to parameterize by width: `openSimdLoop` (2531) `rem = count>>1`→`>>2`;
     `emitSplatZero` (2748) `F64X2_SPLAT`→`F32X4_SPLAT`; `emitHorizontalAdd` (2755) 2-lane→
     **4-lane fold** (extract lanes 0-3, three `F32_ADD`); `emitOddTailGuard` (2769)
     `count&1` single `if` → **`count&3` scalar remainder LOOP** (biggest divergence).
     `simd`/`simdLoad`/`simdStore`/`dataPtr`/`advancePtr(...,16)` are width-agnostic (a v128
     is 16 bytes either way — f32x4 just covers 4 lanes not 2), reuse as-is.
   - Kernels (all ~2487-2745, currently hardcoded f64x2): `compileSimdConstruct`,
     `compileSimdElementwise(F32X4_op, F32_op)`, `compileSimdScale` (`F32X4_SPLAT` needs the
     scalar demoted to f32 first), `compileSimdSum`, `compileSimdDot`. Each branches on the
     operand's `Ty` (F64VEC→existing f64x2 path unchanged/byte-identical, F32VEC→new f32x4
     path). Accumulator stays `allocV128Local()` (v128, same for both). mean/norm are Lisp
     expansions (2394-2407) — width-agnostic, work once the kernels do.
   - `typeOfSimd` (2343): elementwise/constructors return F64VEC today. A single-float vec:
     op is reached only via an `#f`/`make-array 'single-float` OPERAND (there is no
     `vec:zeros`-single constructor — no width param), so make elementwise result-type =
     the operand width (inspect arg types), constructors stay F64VEC. This is the
     llama2-relevant path: `(vec:dot #f(..) #f(..))`.

   **Verify:** `ScalarWasmCompilerTest` asserts on bytecode structure (type bytes 0x7D=f32,
   section shape) — add single-float parity cases mirroring the F64VEC ones; `mvn test`
   GREEN; `wasmtime --invoke` end-to-end (`--no-gc` module is plain MVP + v128, needs NO
   `-W gc`; note wasmtime 46 `--invoke` may not print an f64/f32 return to stdout, so assert
   via an int-returning wrapper or the unit test's structural checks). Keep the F64VEC path
   BYTE-IDENTICAL (the f64x2 branch untouched) — dead-flag/byte-identical proof like the JVM
   `--simd`. Ordering: do 5a (storage) fully + green first, then 5b (kernels).
6. **[DONE 2026-07-08]** **Cross-backend single-float ci-spec + docs (close out Part 1).**
   Most of the `#f`→`#d` reconciliation was PULLED FORWARD into Phase 1 (all test files /
   docs / the compiled-print prefix already flipped). What Phase 6 actually did:
   - **Added ONE ci-spec case `packed-single-float-cross-backend`** (right after the two `#d`
     cases `packed-float-arrays-cross-backend` / `vec-kernels-cross-backend`) — the `#f` /
     `:element-type 'single-float` mirror: rank-2 `#f` literal + `array-rank`/`-dimensions`/
     `aref` (widen), rank-1 `(setf aref)` narrow-store (integer 42), `make-array 'single-float`
     + `:initial-element 2.5` + `length` + `array-element-type` (→ `single-float`), rank-2
     `make-array` fill loop, `row-major-aref`, `coerce → 'list`, `typecase` array, the
     **narrowing boolean** `(= (aref w 0) 0.1)` → `nil`, and the two SAFE reductions
     `(vec:dot #f(1 2 3) #f(1 2 3))` → `14.0` / `(vec:sum #f(2 4 6))` → `12.0`. 11 printed
     lines. Both documented constraints respected (only f32-exact printed values + a `(= …)`
     boolean for the non-exact narrowing; only `vec:` REDUCTIONS, never an element-wise result
     whose width diverges on wasm-GC).
   - **`data-types.md` (en+ja): confirmed already complete** (the `#d`/`#f` prose + runnable
     `(array-element-type #f(1.0 2.0)) ; => single-float` + `make-array … 'single-float`
     examples are present from Phase 1). NO-OP — no doc edit needed.
   - **examples/ snippet: deliberately SKIPPED** (the plan's optional item). The ci-spec case
     + `data-types.md` already cover the cross-backend single-float guarantee, and a standalone
     f32 example would carry the same f32-print-divergence constraint without new verification
     value — the compelling single-float *example* is the Part 2 llama2 demo.
   - **Verification:** pre-checked the exact 11-line output BYTE-IDENTICAL on all four ci-spec
     backends via the jar (interpreter / JVM / WASM-P1 `-W gc` / WASM-component) BEFORE the
     native run; `JvmClassShakerCorpusTest` + `WasmTreeShakerCorpusTest` GREEN (the new case
     compiles + tree-shakes with `--optimize` on JVM and wasm-GC, no decoder gap); **native
     `CiSpecE2eTest` GREEN (728/0)** — the required all-4-backend gate. **Part 1 DONE-line
     reached:** f32 & f64 packed arrays run byte-identical across backends; `#f`/`#d`
     round-trip; `vec:` ops width-polymorphic; linalg behavior unchanged.

   Original plan (as executed) below.

   - Most of the `#f`→`#d` reconciliation was PULLED FORWARD
   into Phase 1 (all test files / docs / the compiled-print prefix already flipped; `mvn test`
   + native `CiSpecE2eTest` were green then). What actually REMAINED:
   - **`data-types.md` (en+ja): ALREADY DONE in Phase 1** — the `#d`(double)/`#f`(single)
     prose + `(array-element-type #f(1.0 2.0)) ; => single-float` example are present. Only
     add 1-2 more `#f` example lines IF a runnable single-float example adds value (verify via
     `DocExamplesTest`; keep en+ja byte-identical fences). Likely a no-op.
   - **Add ONE `#f` cross-backend `ci-spec.yaml` case** (`packed-single-float-cross-backend`,
     mirror `packed-double`/`vector-literals-cross-backend`). ci-spec runs interpreter / JVM /
     WASM-P1 / WASM-component (NOT `--no-gc` — that's covered by the new
     `WasmLispCompilerIntegrationTest` invoke E2E). **TWO hard cross-backend constraints the
     case MUST respect (both already learned, do NOT relitigate):**
     1. **f32 print divergence** — the WASM double-printer renders a non-terminating decimal
        differently (`0.10000000149011612` → `0.1`). So only ever PRINT f32-exact values
        (integers, 0.5, 0.25, …), the `single-float` symbol, and vectors of exact values; for
        any NON-exact narrowing proof use a BOOLEAN `(= (aref v i) 0.1)` → `nil` (identical on
        all 4 backends: each stores f32(0.1) and widens-compares to f64 0.1).
     2. **`vec:` element-wise/constructor RESULTS on `#f` diverge in WIDTH on wasm-GC** — the
        `#+rontolisp-wasm` double-only `%make-like` makes `(vec:add #f.. #f..)` print `#d(...)`
        on wasm-GC but `#f(...)` on interp/JVM (KNOWN, documented Phase 4). So the ci-spec case
        must NOT print a `vec:` element-wise/constructor result on `#f`. `vec:` REDUCTIONS
        (`sum`/`dot`/`mean`/`norm`) are SAFE — they return an f64 scalar computed identically
        on all backends (`(vec:dot #f(1 2 3) #f(1 2 3))` → `14.0` everywhere).
     - Safe case content: `aref`/`(setf aref)`/`array-element-type`/`length`/print an
       exact-valued `#f` vector/`coerce … 'list`/a `(= …)` narrowing boolean/a `vec:dot` or
       `vec:sum` scalar. (Essentially the interpreter+JVM+WASM `#f` unit tests already GREEN in
       `WasmLispCompilerIntegrationTest` lines ~5030-5106, lifted into one ci-spec program.)
   - **Native `CiSpecE2eTest` all-4-backend verify** (REQUIRED once `ci-spec.yaml` changes):
     `./mvnw -Pnative clean package -DskipTests` then
     `./mvnw -Dtest=CiSpecE2eTest -Drontolisp.binary="$PWD/target/rontolisp" test`. A failure
     names `[case '<name>' on <BACKEND>`.
   - Optional: a small `examples/` single-float snippet if useful for the site.

**Done-line:** f32 & f64 packed arrays run byte-identical across backends; `#f`/`#d`
round-trip; simd ops width-polymorphic; linalg behavior unchanged (prints `#d`).

## Part 2 — `vec:matvec` (GEMV) (the llama2 payoff; depends on Part 1's f32 path)

(Named `vec:matvec` per decision #5 / `.todo/96` -- do the `simd:`→`vec:` rename first, then
build this under the new name. It is matrix×vector→**vector** (GEMV), which is why it belongs
in `vec:`; general matrix×matrix `matmul` stays in `linalg:`.)

- Add `vec:matvec` — **GEMV** (matrix `W(d,n)` × vector `x(n,)` → `xout(d,)`), which is
  llama2's actual op (every projection/FFN/classifier): `y[i] = dot(row_i of W, x)`. GEMV is
  the **CLEAN** case: `W` rows are contiguous, `x` is contiguous → **no transpose**; the inner
  loop is a vectorized dot (FMA-accumulate + `reduceLanes`) = `JvmSimdVectorTemplate.dot` run
  `d` times in ONE bridge call (amortizes bridge entry over the whole matrix vs a `d`-times
  `vec:dot` loop).
- **Both widths:** f32 (`FloatVector`, llama2) + f64 (`DoubleVector`). The f64 GEMV can land
  first (independent of Part 1); the f32 GEMV needs Part 1.
- mat×vec is all llama2's single-token decode needs; full mat×mat **GEMM** (strided `B`
  column → transpose-`B`) is NOT required — defer/skip unless prefill batching is wanted, and
  if ever added it goes to `linalg:` (matrix-producing), not `vec:`.
- Gate on `--simd` + `programUsesAnyAcceleratedSimdOp` (add `matvec` to the walk).
  Byte-identical vs the scalar `vec.lisp` fallback (`JvmSimdAccelCompilerTest`-style) +
  dead-flag proof. Determinism: SIMD reduction associativity → last-ULP; for ci-spec /
  cross-backend use inputs whose float results are exact (power-of-two / integer-valued).
- **Files:** extend `JvmSimdVectorTemplate` (matvec f32+f64), `JvmSimd{Compiler,
  RuntimeBuilder}`, `LispNames`/`PackageRegistry` vec-functions (`vec:matvec`), `vec.lisp`
  scalar fallback; `ScalarWasmCompiler` `f32x4` GEMV on `--no-gc` optional.

**Done-line:** `vec:matvec` accelerated f32+f64, byte-identical, dead-flag-proven; a
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
