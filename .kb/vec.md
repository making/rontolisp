# vec: package + packed float-array acceleration

`vec:` is a set of portable packed-`f64` vector kernels over the dedicated packed float-array
type. The representation itself lives in `LispFloatArray`, `JvmFloatArrayRuntimeBuilder`,
`WasmArrayCompiler` (`$farray`) and `NoGcWasmCompiler` (`F64VEC`).

## The type it rides on

A vector is a rank-1 packed `(array double-float)` -- the same unboxed array `#d(...)` and
`(make-array n :element-type 'double-float)` produce, so generic `aref`/`(setf aref)`/`length`/
`make-array` interoperate everywhere. Storing a non-real is a type error; there is no
boxed/general fallback. `#f(...)` / `:element-type 'single-float` is a DIFFERENT width; `vec:` is
WIDTH-POLYMORPHIC over both -- element-wise kernels preserve the input width (`vec::%make-like`),
reductions fold to an f64 scalar.

- Interpreter: `record LispDoubleFloatArray(double[] data, int[] dims)`, one width of the sealed
  `LispFloatArray` umbrella (`LispSingleFloatArray(float[])`, `LispBFloat16Array(short[])` are the
  others, `.kb/bfloat16.md`). No in-array header.
- JVM: a bare primitive array with an embedded header whose LAYOUT IS WIDTH-DEPENDENT, owned by
  `codegen/jvm/JvmPackedFloatWidth`: `double[]`/`float[]` are `[rank, dim..., data...]` (data offset
  `1 + rank`), bfloat16 `short[]` is `[rank, hi_0, lo_0, ..., data...]` (offset `1 + 2 * rank`;
  a `short` cannot hold a dimension above 32767). **No emitter spells the offset itself** -- every
  header read/write goes through that enum's emit methods, because a site hard-coding `1 + rank`
  reads a length-1 bfloat16 array's rank word as its element, silently, and only a comparison
  against the interpreter shows it (`JvmBFloat16ArrayTest` runs every case on both backends).
- wasm-GC: `TYPE_FARRAY` struct whose data field holds `TYPE_F64ARR = (array (mut f64))` or
  `TYPE_F32ARR = (array (mut f32))`, told apart by `ref.test $f32arr` -- except under `--simd`,
  where it holds a `TYPE_VBLOCK` over `(array (mut v128))` (layer 3; the struct type is unchanged,
  the field being `(ref null eq)` either way).
- `--no-gc`: `[count:i32][count f64]` (`Ty.F64VEC`, 8-byte stride) or `[count:i32][count f32]`
  (`Ty.F32VEC`, 4-byte stride) in linear memory, keyed off the `#d`/`#f` literal or `:element-type`.

## Asking a packed array its width

`LispFloatArray` is sealed, so EVERY width test must be an exhaustive `switch` over it with NO
`default` arm -- never `instanceof LispSingleFloatArray` read negatively as "therefore double", and
never a cast straight to one record. A width added to `permits` must become a compile error at
every deciding site rather than a silent fall into the double arm.

Sites live in `eval/Environment`, `eval/LinalgBlas`, `eval/VecSimd`, `eval/LinalgSimd`,
`eval/LinalgGpu` and nowhere else in `src/main` (86 as of 2026-09-03). MEASURE the count, never
count by hand: add a throwaway third permit plus its `permits` name and run `./mvnw compile`.

- **The rule is not "no `default`" -- an arm matching more than one permit IS a default however
  spelled.** `NoGcWasmCompiler.typeOf` had `case LispFloatArray ignored -> Ty.F64VEC` after its
  single-float arm and `compileFloatArrayLiteral` opened with
  `boolean single = fa instanceof LispSingleFloatArray`; a bfloat16 literal was emitted as F64VEC,
  eight bytes an element, wrong layout, no diagnostic. A supertype pattern is correct exactly when
  the arm's answer is width-INDEPENDENT (`arrayp`, a rank, a total size, a no-op walk arm).
- **Exhaustiveness is checked in the STATEMENT form for the sealed type, and only in the EXPRESSION
  form for the width enum** (JLS 14.11.2: a switch is enhanced, hence exhaustive-checked, when the
  selector is not a legacy type -- `char`/`byte`/`short`/`int` + boxes, `String`, ENUM -- or a label
  is a pattern or `null`). So write a `FloatWidth` switch as an EXPRESSION; sealed-type sites
  already have the property and must not be churned for it.
- `FloatWidth` and the permits are in bijection (`FloatWidthTest`): switch over the sealed type for
  the concrete array, over `LispFloatArray.width()` for the width as a VALUE (the
  `%la-gather-strided` wire, nothing else). The bijection catches what the compiler cannot -- a
  CONSTANT added ahead of its permit, leaving every enum switch looking exhaustive.
- Three shapes deliberately stay `instanceof` and the probe does not report them: a same-width guard
  INSIDE an arm (a mismatch declines to the lane kernel or the defun); an explicit must-be-double
  requirement (the eleven-element Adam rule vector and the three-word generator state --
  `%la-adam-step`, `%la-rng-fill`, `%la-dropout-mask`); and four narrowing helpers
  (`floats`/`doubles` in `LinalgBlas`/`LinalgSimd`/`LinalgGpu`) reading a SECOND operand already
  proven to share the first's class, each called only inside an exhaustive arm.
  `eval/PackedBuffer.of` and `eval/GeomKernels` also name the two records: positive tests whose
  fallthrough is a decline (`geom:` is f32-only).
- **The one width test that cannot be a compile error** is `make-array`'s in `Environment`: it
  dispatches on the `:element-type` STRING. A new width must be added there by hand.

## vec.lisp = the scalar reference / cross-backend oracle

`src/main/resources/am/ik/rontolisp/eval/vec.lisp` defines every `vec:` function as a plain `defun`
over `make-array :element-type 'double-float` / `aref` / `length`. It is the implementation on the
interpreter (unless `--simd`), the JVM and wasm-GC, and the ORACLE for accelerated paths.
`VecLibrary` splices/loads it like `LinalgLibrary`: interpreter lazy-loads on the first resolution
of a `vec:`-qualified function; `RontoLispCli` / `RontoPlayground` / corpus+e2e helpers call
`VecLibrary.process(program)` after user-macro expansion; `--no-gc` is gated OFF the splice
(`RontoLispCli`: `!(outputFile.endsWith(".wasm") && noGc)`) and intercepts the whole surface
natively.

Members: `zeros`/`ones`/`arange`/`from-list`/`to-list` (the first three take `:element-type` through
`vec::%make`); `aref`/`aset`/`length`; `add`/`sub`/`mul`/`div`/`scale` plus the CL operator
spellings `+`/`-`/`*`/`/`, which are STRICTLY BINARY aliases (unlike the n-ary `linalg:` siblings:
every `vec:` kernel is fixed-arity and allocation-explicit -- the reason `-into` exists -- so an
n-ary spelling silently allocating an intermediate per operand would fight the contract, and
`--no-gc` has no cons list to fold over; every layer maps the alias onto the SAME kernel, so the
one-line defun never runs on an accelerated build); the unary ufuncs `exp`/`log`/`tanh`/`sin`/`cos`/
`tan`/`asin`/`acos`/`atan`/`sinh`/`cosh`/`sqrt`/`abs`/`square`/`negative`/`sign`/`reciprocal`; the
selects `maximum`/`minimum`/`relu`/`clip`; the reductions `sum`/`dot`/`mean`/`norm`; and `matvec`
(GEMV: rank-2 x rank-1 -> fresh rank-1; the defun reads `(aref w i j)` over `(array-dimensions w)`
and allocates via `vec::%make-like`).

`from-list`/`to-list` need cons lists, so they are portable-backends-only (a `--no-gc` compile
error). `(setf (vec:aref v i) x)` -> `(vec:aset v i x)` via `LispMacroExpander.expandSetf`
(`VEC_QUALIFIED_AREF`).

`matvec` is the ONE `vec:` member `--gpu` intercepts (allocating form only) -- interpreter through
`LinalgGpu.installVec` (called from the vec lazy-load hook on top of whatever `VecSimd` bound), JVM
through `JvmSimdCompiler.compileGpuMatvec`. The device takes it above 2^17 matrix elements and only
over a matrix it has been offered before and that has not been written since (a GEMV is one pass, so
it pays only when the matrix is already resident); its kernel accumulates in DOUBLE like the defun,
not in lanes. `.kb/gpu.md`. `matvec`/`matvec-into` are also the two members `--parallel` splits
across threads (`.kb/simd-parallel.md`): the same row chains over a row range per thread,
bit-identical to serial; no reduction is.

The packed array is also a JAVA boundary type: `rontolisp:jvm-export`'s
`:float-vector`/`:float-matrix` hand one over as `am.ik.rontolisp.runtime.RontoFloatArray`, a handle
that ALIASES it (no copy at either crossing -- a copying boundary measured ~10x the kernel) and
whose class files travel beside the compiled class. [jvm-export.md](jvm-export.md).

## Destination-passing `-into` kernels

Every vector-returning kernel has an `-into` sibling (`add-into`..`scale-into`, `matvec-into`, the
unary `exp-into`..`reciprocal-into`, `maximum-into`/`minimum-into`/`relu-into`/`clip-into`) writing
into a caller-supplied destination (argument 1, CL's `map-into` order) and RETURNING it. Reductions
have none.

Which backend leaks: a packed array is a GC object on the interpreter, the JVM and wasm-GC (with and
without `--simd`) -- all four reclaim it. Only `--no-gc` bump-allocates in linear memory with NO
FREE, reclaiming by popping the whole arena at an export boundary (auto-reset on a scalar return,
`__ronto_alloc_mark`/`_reset` for a host), never within one call. `-into` makes the bump high-water
equal the live set, so freeing becomes an optimization rather than a correctness requirement.
Elsewhere `-into` is purely an allocation-rate optimization. `linalg` arrays behave identically.

- **Aliasing**: element-wise kernels tolerate `out` aliasing `a` and/or `b` (element `i` depends
  only on element `i`; within a lane block reads precede the store), so `(vec:add-into acc acc d)`
  is the intended in-place accumulation. `matvec-into` does NOT (`out[row]` folds over all of `x`).
  The `eq` guard is in the defun AND repeated in `VecSimd` / `JvmSimdVectorTemplate`, because
  accelerated call sites REPLACE the defun; interpreter/JVM compare the BACKING array
  (`r.data() == vx.data()`), the real sharing condition.
- **Widths** must match across `out` and the operands. `out`'s length is NOT checked, matching
  `vec:add`'s behavior on unequal-length operands.
- Per backend: `eval/VecSimdKernels` (`addInto`..`matvecIntoF`) + `VecSimd.installInto`
  (interpreter); `JvmSimdVectorTemplate.simdAddInto`..`simdMatvecInto` + `JvmSimdCompiler.ARITIES`
  (arity 3, a `ternaryDesc` in `JvmSimdRuntimeBuilder`) (JVM);
  `WasmVecSimdRuntimeBuilder._vec_add_into`.._vec_matvec_into` (a `boolean into` through
  `buildElementwise`/`buildScale`/`buildMatvec`) (wasm-GC `--simd`); `NoGcWasmCompiler` threads
  `boolean into` through `compileSimd/ScalarElementwise{,F32}` and `compileSimd/ScalarScale{,F32}`
  -- `-into` skips `allocVec` and uses the caller's block as `dp` (`compileVecArg`/`loadVecCount`),
  so the loop bodies are unchanged; its `matvec-into` alias guard is a runtime pointer-equality
  `unreachable` trap (no error channel there), the analog of wasm-GC's `ref.eq` trap.
- The property it buys, `--no-gc --simd`, 12000 accumulations over 65536 elements: `add-into` peaks
  at 13.7 MB; `add` peaks at 4.31 GB then traps (`memory.grow` fails, so the store goes out of
  bounds). `NoGcWasmCompilerTest.intoKernelsCallTheBumpAllocatorOnlyForTheConstructors` pins it
  structurally (2 `allocVec` sites vs 3).

## Comparison-select ufuncs

`maximum`/`minimum` (binary), `relu` (unary), `clip` (unary + two scalar bounds), each with `-into`,
in BOTH packages (linalg has `maximum`/`minimum` kernels only; `clip` =
`(minimum (maximum a lo) hi)` and `relu` = `(maximum a 0.0)` compose them).

The oracle is the STRICT COMPARISON SELECT -- `(if (> x y) x y)`, `(if (< x y) x y)`,
relu `(if (> x 0.0) x 0.0)`, clip the min-max nesting -- NEVER an IEEE min/max primitive
(`Math.max` propagates NaN from either side and orders -0.0 below 0.0; wasm `f64x2.min/max`
propagate NaN too). The SECOND operand or the bound wins any false comparison, so
`(vec:maximum #d(-0.0) #d(0.0))` is `#d(0.0)`, `maximum(x, NaN)` keeps the NaN, relu maps NaN/-0.0
to 0.0, clip sends NaN to lo and inverted bounds (lo > hi) to hi. Unlike the transcendentals this is
CROSS-BACKEND-identical (`>`/`<` are IEEE everywhere and a select only copies bits), so `--no-gc`
matches and ci-spec carries the -0.0 tie (`comparison-select-ufuncs-cross-backend-cases`).

An f32 lane/native compare equals the defun's widened compare (widening is exact and
order-preserving), so array-array selects lane-ize at BOTH widths: interpreter/JVM keep plain scalar
select loops (bits cannot depend on lane grouping, so lane forms are perf-only; the JVM linalg lane
blocks are gated `op <= OP_DIV`), wasm-GC runs `WasmVecLoops.gcMap2Select` (gt/lt mask +
`v128.bitselect`; `F32X4_GT`/`F64X2_GT` in `Instruction` + `WasmSections.skipSimd`), relu is the
`U_RELU` gcMap1/simdMap1 lane form, and `--no-gc` uses `simdMap2Select` / `scalarMap2Select`
(compare + core `select`). Scalar-vs-array shapes keep the widen rule: linalg's f64-scalar broadcast
lane-selects (`gcBroadcastSelectF64`, WITH the save/restore bracket -- a select over the padding can
answer s), its f32-scalar broadcast walks `_v_get`/`_v_set` widened against the FULL double scalar,
and clip compares widened elements against full-double bounds in an element loop everywhere
(`buildClip`, `compileSimdClip`). vec FUNC_COUNT 47 -> 55 (MAXIMUM..CLIP_INTO; CLIP_INTO is the
first `TYPE_CALLABLE_BASE + 3` four-param kernel), linalg 30 -> 32, `userFuncBase()` shift 77 -> 87.

## Element-wise unary ufuncs

`exp`/`log`/`tanh`/`sin`/`cos`/`tan`/`asin`/`acos`/`atan`/`sinh`/`cosh`/`sqrt`/`abs`/`square`/
`negative`/`sign`/`reciprocal` (+ `-into`) in BOTH packages under their numpy names. They emptied
`BuiltinFunctionWrappers.WASM_UNSUPPORTED` -- every transcendental built-in now compiles on WASM.

WASM software scalars and their accuracy contracts:

- `WasmAtanCompiler`: odd/reciprocal folds (mapping +-inf to +-pi/2 for free, 1/inf = 0) + two
  half-angle folds + a 10-term Taylor series over z = u^2, ~1e-15 relative; NO `i32.trunc`, so
  NaN/inf flow through with no edge branch and `-0.0` is PRESERVED (unlike the signum class).
  asin = `atan(x / sqrt((1-x)(1+x)))` (factored radicand, accurate near |x|=1);
  acos = `2*atan(sqrt((1-x)/(1+x)))`, NOT `pi/2 - asin`, so `(acos 1)` is exactly 0.0; |x| > 1 ->
  NaN.
- `WasmSinhCoshCompiler`: e = exp(|x|) via the shared `WasmExpCompiler.emitExpCore`; sinh =
  sign-restored `(e - 1/e)/2` with an odd Taylor branch below |x| = 0.25 killing the cancellation
  (and, applied to x directly, preserving -0.0); cosh = `(e + 1/e)/2`. Accuracy tracks the software
  exp (inf near |x| ~ 755); NaN/+-inf branches must PRECEDE the exponential, because the exp core's
  Horner maps -inf to +inf.
- `WasmSinCosCompiler`: Cody-Waite reduction `k = nearest(x*2/pi)`,
  `r = (x - k*pio2_1) - k*pio2_1t` (the fdlibm two-part split), quadrant `trunc(k) & 3` sign/swap,
  degree-11/12 Taylor polynomials; ~1e-11 relative for |x| <= ~1e6, a 2*pi pre-fold + clamp above
  2^30 keeping huge args finite but progressively meaningless; NaN/+-inf -> NaN;
  `(sin -0.0)`/`(tan -0.0)` = `0.0` (the signum-class edge); `tan` is the sin/cos ratio from ONE
  reduction, error amplified near the poles.
- `WasmLogCompiler`: exponent extraction + an atanh series, ~1e-10 relative. `WasmTanhCompiler`:
  `(e^(2x)-1)/(e^(2x)+1)` over the software exp with the doubled argument clamped to +-40 so large
  inputs saturate to exactly +-1.0; `(tanh -0.0)` is `0.0`.

- **The oracle is each backend's OWN scalar defun** (the emap rule: read widened to f64, apply the
  backend's scalar op, narrow on store). Interpreter/JVM use `Math.exp/sqrt/abs/signum` and true
  negation; wasm's variable-path `abs` and unary minus now agree (`f64.abs`/`f64.neg` on the branch
  that established a float operand, replacing `_rat_cmp`'s `x < 0 ? 0 - x : x` and
  `_rat_sub(0, x)`), while its `signum` still maps `-0.0`/NaN to `0.0`. The one edge where wasm's
  `exp` is EXACTLY the JVM's is underflow: `WasmExpCompiler.UNDERFLOW_CLAMP` clamps the reduced
  Horner polynomial at `f64.max(p(t), 0.0)` before the squarings, because `p` goes negative below
  its real root (`t = -2.18`, `x = -558`) and the even squaring count turned that into a huge
  POSITIVE value (`(exp -1000)` was `2.4e125`, `(exp -inf)` was `+inf`). The clamp is a no-op
  wherever `p(t) >= 0` and is what makes a `-infinity` mask reach `linalg:softmax` as `0.0`
  everywhere (`.kb/linalg.md`); `emitExpF64` carries the same instruction so `--simd`/`--no-gc`
  kernels stay bit-identical to the defun. Consequence: each `--simd` kernel mirrors ITS backend's
  defun, per-backend bit-identity holds, and cross-backend `-0.0`/NaN/low-digit output stays OUT of
  ci-spec (only the exact probes `(log 1)`/`(tanh 0)`/`(tanh +-25.0)` in
  `log-tanh-exact-cross-backend-cases` and `(sin 0)`/`(cos 0)`/`(tan 0)`/`(sin (/ pi 2))`/`(cos pi)`
  in `sin-cos-tan-exact-cross-backend-cases`).
- **Lane forms only where they equal the defun.** Interpreter/JVM lane-ize sqrt, abs, negative and
  reciprocal (broadcast(1)/v); exp, log, tanh and sign stay de-boxed scalar loops
  (`VectorOperators.EXP` etc. are NOT bit-identical to `Math.exp`; the gate is
  `JvmSimdVectorTemplate.hasLaneForm`). wasm-GC lane-izes sqrt/neg/abs (native `f64x2/f32x4`
  instructions) and reciprocal (div-from-splat-1); exp, log, tanh, sin, cos, tan and sign walk
  `_v_get`/`_v_set` element loops emitting the defun's exact f64 sequence (the compilers' constants
  are package-private for that; shared raw-f64 emitters are
  `WasmVecSimdRuntimeBuilder.emitExpF64`/`emitLogF64`/`emitTanhF64`/`emitSinCosF64`/`emitSignumF64`,
  dispatched via `SCALAR_OP_*` + `emitScalarUnaryF64`). All f32 lane forms are exact by the
  `53 >= 2*24+2` bound or correct rounding, so `#f` results equal the widen-compute-narrow defun bit
  for bit.
- **`square` and `reciprocal` ride existing kernels where a defun exists**: `vec:square` =
  `(vec:mul v v)`, `linalg:square` = `(linalg:mul a a)`, `linalg:reciprocal` = `(linalg:div 1 a)` --
  accelerated transitively, never intercepted (the guards pin `#'vec:square` as `#<lambda>`).
  `vec:reciprocal` has its own kernel (vec: has no div).
- **`--no-gc`**: the five arithmetic ufuncs (+`-into`) lower natively (`WasmVecLoops.simdMap1`/
  `scalarMap1`, native IEEE `f64.abs`/`f64.neg` since there is no defun to mirror);
  `vec:exp`/`sign`/`log`/`tanh`/`sin`/`cos`/`tan` (+`-into`) lower through
  `NoGcWasmCompiler.compileSimdUnaryF64`, a one-element-per-iteration loop over the SAME raw-f64
  emitters wasm-GC uses, widening f32 on read and narrowing on store. No lane form exists for these,
  so BOTH `--simd` modes emit the identical loop (no `0xFD`). The scalar
  `(exp x)`/`(log x)`/`(tanh x)`/`(sin x)`/`(cos x)`/`(tan x)`/`(signum x)` builtins themselves
  remain unknown on `--no-gc`.
- New v128 opcodes (`f32x4/f64x2.sqrt/abs/neg/lt`, `v128.bitselect`) go in `am.ik.wasm.Instruction`
  AND `WasmSections.skipSimd` (which throws on unknown 0xFD).

Pinned by the unary-ufunc blocks in `eval/VecSimdTest`, `eval/LinalgSimdTest`,
`JvmSimdAccelCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `NoGcWasmCompilerTest`
(`expAndSignLowerNativelyOnNoGc`, `logAndTanhLowerNativelyOnNoGc`, `sinCosTanLowerNativelyOnNoGc`:
a distinctive f64 constant present, no `0xFD` in either mode, `-into` skips the allocator) and
`WasmLispCompilerIntegrationTest` (`wasmGcSimdUnaryUfuncsAreByteIdenticalToTheScalarPath` and its
linalg twin, `noGcRunsUnaryUfuncsUnderBothLowerings`, `noGcRunsExpAndSignUnderBothLowerings`,
`noGcRunsLogAndTanhUnderBothLowerings`, `noGcRunsSinCosTanUnderBothLowerings` -- the nontrivial
probes compare a `--no-gc` run against a wasm-GC run, not a hardcoded constant; the scalar-builtin
tests are `logSoftwareApproximation`/`tanhSoftwareApproximation`/`sinCosTanSoftwareApproximation`,
tolerance 1e-5 = print precision, not approximation precision).

## Layer 4 -- `--blas` / `--gpu` over the GEMV pair, opt-in

The other layers are `--simd`: one lane kernel per member, TOTAL (packed float arrays of one width,
signal on anything else), so the JVM call site is a bare `INVOKESTATIC` and the interpreter native
never declines. `vec:matvec`/`vec:matvec-into` are the exception: `--gpu` takes `vec:matvec` (the
one device member outside `linalg:`, `.kb/gpu.md`), `--blas` takes BOTH as `cblas_?gemv`
(`.kb/linalg-blas.md`).

Both are PARTIAL and neither flag implies `--simd`, so these two call sites are a guarded CHAIN:
device -> library -> lane kernel -> spliced defun, over one set of temps, each rung answering `null`
for what it declines and the bottom rung total. `JvmSimdCompiler.compileMatvecChain` emits it
(claimed by `JvmExprCompiler` whenever the `--blas` or `--gpu` bridge was emitted; a `--simd`-only
build keeps layer 1's bare `INVOKESTATIC` byte for byte); on the interpreter the same order is
install order: `VecSimd.install` -> `LinalgBlas.installVec` -> `LinalgGpu.installVec`, each
capturing whatever the name was bound to and declining back to it.

Precision: a library gemv reorders the `#f` fold, so it is CLOSE to the lane kernel rather than
equal -- up to 5.5e-3 relative on llama2's classifier-head shape, enough to move an `argmax`. The
examples pinning derived integers (`simd-gemv`, `tiny-llm`, `llama2`) are therefore RUN under the
flag rather than assumed.

## Layer 0 -- interpreter `--simd` (jdk.incubator.vector), opt-in

`rontolisp prog.lisp --simd` (interpret, no `-o`) runs the eight vectorizable kernels on the Vector
API instead of the defuns. The DEFAULT interpreter is unchanged -- it is the cross-backend
byte-identity oracle, and `ci-spec.yaml` never passes `--simd`.

- `eval.VecSimdKernels` -- the lane loops, `static`, over BARE `double[]`/`float[]` + explicit
  `rows`/`cols` (no in-array header, unlike the compiled repr). Mirrors `JvmSimdVectorTemplate`
  operation for operation (same `SPECIES_PREFERRED` element-wise, same
  `FSPECIES_REDUCE = FloatVector.SPECIES_128` pin on f32 reductions, `THRESHOLD = 128`,
  two-rounding mul-then-add, f32-throughout f32 reductions, f64-then-narrow `scaleF`), so
  interpreter `--simd` == compiled `.class --simd` bit for bit. NOT reused from `codegen.jvm`
  (`eval` may not depend on it, and the template assumes the header-in-array layout).
- **f32 reductions are conversion-free.** `sumF`/`dotF`/`matvecF`/`matvecIntoF` used to widen every
  f32 lane via `FloatVector.convert(F2D, part)` to match the f64-accumulating scalar oracle. That is
  the Vector-API op most likely to be missing from a JIT's intrinsics (one compiler family emulates
  it lane by lane), is never free even when intrinsified, and bought a bit-identity the WASM kernels
  never honoured. Now every `--simd` backend accumulates an f32 reduction in f32 and promotes ONCE,
  at the value boundary, so all four agree; the scalar `vec.lisp` reference stays the more accurate
  oracle. `#d` is untouched (`DoubleVector`, `SPECIES_PREFERRED`, f64 accumulator).
- **The lane-count pin.** An f32 reduction's value depends on the lane count (`2^24 + 768` with 4
  lanes, `+ 896` with 8, `+ 960` with 16), so `FSPECIES_REDUCE` is `SPECIES_128`, not
  `SPECIES_PREFERRED`, in BOTH kernel files: a compiled `.class`/native binary must not answer
  differently on an AVX-512 host, and the WASM kernels are always `f32x4`. Element-wise f32 kernels
  keep `SPECIES_PREFERRED` (bit-exact at any width), as do the f64 reductions.
- **The GEMV row has FOUR accumulators above a column gate**, in all four `--simd` implementations
  at once (one pinned `f32x4` accumulator per row is one dependency chain, and the chain bounds a
  row long before memory does).
  - No fused multiply-add: it measured level with plain mul-then-add, and wasm SIMD has no
    deterministic one (`relaxed_madd` is explicitly allowed to differ between engines, so it can
    never carry a bit-identity contract).
  - **The gate is `2 * MATVEC_ACCUMULATORS * lanes = 32` COLUMNS**, derived from the kernel's shape:
    below two full wide iterations the four zeroed vectors and the three-add fold are most of the
    row. It sits under every real head dimension and above `MATVEC_ROW_THRESHOLD = 16`, so a row
    between the two gates runs the single chain it always ran.
  - **The gate must be a pure function of the COLUMN count.** An attention `V^T . att` is a GEMV
    whose columns are the sequence length, so one call site crosses the gate as generation proceeds.
    Row counts are deliberately NOT consulted even though they would predict better (four
    accumulators lose only at one or two rows): that would make the answer depend on something the
    four implementations cannot agree on call for call. Do not "improve" this with the row count.
  - 32 is a PERFORMANCE number and machine-dependent (measured on aarch64); the lane-count pin above
    is a CORRECTNESS one and is not. The f64 `matvecRows` still has one chain and is UNMEASURED.
- `eval.VecSimd` -- `available()` (links the kernels class; a `NoClassDefFoundError` without the
  incubator module becomes `false`) and `install(Environment)` (native `LispFunction`s for
  `vec:add`..`vec:matvec`, overriding the just-evaluated defuns; `mean`/`norm` keep their scalar
  bodies and pick the natives up through the Lisp-2 global function namespace). These two methods
  are the ONLY callers of `VecSimdKernels`.
- `LispEvaluator.setSimd(true)` -> `VecSimd.install(globalEnv)` right after the `vec.lisp` forms are
  evaluated in `resolveFunction`'s lazy-load hook. `RontoLispCli.interpret`/`.repl` thread `--simd`
  through the shared `enableSimd` helper, probing `available()` first: absent module -> a one-line
  note + the scalar reference (a graceful fallback, unlike the compiled class's hard dead-flag
  guard). The interception is evaluator-level, so `rontolisp --simd` accelerates the REPL too.
- **Native binary**: the `native` profile passes `--add-modules jdk.incubator.vector` +
  `-H:+VectorAPISupport` (build-time only). Without the latter the Vector API falls back to per-lane
  emulation 6-32x SLOWER than scalar, so it is effectively mandatory. GraalVM 25 refuses to combine
  it with `-H:+SharedArenaSupport`, which the JLine FFM terminal provider needs (it closes an
  `Arena.ofShared` from its signal handler on REPL shutdown), so
  `JLineRepl.selectNativeImageTerminalProvider()` pins `org.jline.terminal.provider=jni` in the
  image and the pom drops `SharedArenaSupport`. Forcing
  `-Dorg.jline.terminal.provider=ffm` on the binary reproduces the old crash; that is the pin.
- **Web Image**: `src/web/java/.../Target_VecSimd.java` substitutes `available()` (-> `false`) and
  `install(...)`, making `VecSimdKernels` unreachable so the incubator module never enters the
  browser image. It suffices only because those two are the ONLY entry points into the kernels (the
  `-into` references sit in the private `installInto`/`defineInto`). A new PUBLIC `VecSimd` method
  touching the kernels would break it, and only the Pages workflow's Web Image build would notice.
- Tests: `eval/VecSimdTest` (every kernel vs the oracle at both widths, below and above `THRESHOLD`;
  the `#<function vec:dot>` vs `#<lambda>` interception guard; mixed-width and rank errors) plus
  `singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`.

## Layer 1 -- JVM `--simd` (jdk.incubator.vector)

`--simd` routes the eight vectorizable kernels (`add`/`sub`/`mul`/`div`/`scale`/`dot`/`sum`/
`matvec`, plus the operator aliases onto the same four bridges) at their call sites to an embedded
bridge, replacing the defun. `mean`/`norm` accelerate transitively.

- `JvmSimdVectorTemplate` -- the Vector-API kernels (plain Java compiled by the project; the pom
  adds `--add-modules jdk.incubator.vector` to javac + surefire). Unbox is trivial and zero-copy:
  cast `(double[]) arg`, `off = 1 + (int)arg[0]`, read via
  `DoubleVector.fromArray(SPECIES, arr, off + i)`; the result is a fresh packed `double[]`.
  `THRESHOLD = 128` gates the lane loop vs a scalar loop; the dot's two-rounding mul-then-add (not
  fma) keeps the only scalar-vs-vector divergence to reduction associativity. The lane sum is a
  manual ascending-index scalar loop (`sumLanes`/`sumLanesF`), NOT `reduceLanes(ADD)` -- see
  `.kb/simd-parallel.md` for why.
- Width-polymorphic bridge: each kernel dispatches on the runtime backing (`double[]` ->
  `DoubleVector`; `float[]` -> the `FloatVector` sibling, element-wise to a fresh `float[]`,
  `sum`/`dot` to an f64 scalar accumulated in f32 over four pinned lanes and promoted once). Mixed
  single/double operands are a hard `IllegalArgumentException`.
- `simdMatvec` (GEMV): the `simdDot` lane loop once per row of a rank-2 `W` -- header
  `[2, d, n, ...]` so `ow = 1 + (int)W[0] = 3`, `d = (int)W[1]` rows, `n = (int)W[2]` cols;
  `r[2 + row] = dot(W row, x)` into a fresh length-`d` vector. ONE bridge call covers the whole
  matrix. `matvecF` mirrors `dotF`, each row's f32 acc stored straight into the `float[]`.
- `JvmSimdRuntimeBuilder` reads the template `.class` from the classpath, renames it into the
  program's package (`RontoLispSimdBridge`), base64-embeds it, emits `_simdInit` (a
  `Lookup.defineClass` guarded by `_simdInited`), like the `java:` bridge. `JvmSimdCompiler.compile`
  emits the call site, wired in `JvmExprCompiler`
  (`ctx.simdOps != null && JvmSimdCompiler.handles(...)`).
- Gate: `JvmLispCompiler` computes `usesSimd = simdAccel && programUsesAnyAcceleratedSimdOp` ->
  `Ctx.simdOps`. `JvmArrayRuntimeBuilder` / the `_fv*` packed helpers are UNCHANGED (packed is a
  separate repr and the bridge result is rendered/indexed by the same helpers). Getting the
  acceleration out of a `--simd` class needs `java --add-modules jdk.incubator.vector`; the default
  build is byte-identical and needs no module.
- **Module-absence degrade**: `_simdInit`'s `Lookup.defineClass` still fails to LINK without the
  module (`LinkageError`/`NoClassDefFoundError: jdk/incubator/vector/Vector`), but `_simdInit`
  CATCHES it, warns once on stderr, and leaves `_simdAvailable` false; every accelerated call site
  checks `_simdReady()` before resolving a reference into the bridge and falls back to the defun --
  the same degrade `--blas`/`--gpu` give with no library/device. Verified via
  `embedsBridge`/`JvmSimdModuleFallbackTest`.
- Because the spliced `mean`/`norm` bodies always call `sum`/`dot`, ANY `--simd` program using the
  vec package embeds the bridge (dead defuns are shaken by `--optimize`).

## Layer 2 -- `--no-gc`: native v128 under `--simd`, scalar loops by default

`NoGcWasmCompiler` lowers the whole `vec:` surface itself. `--simd` (ctor `this.simd`) is the
switch: with it the vectorizable kernels lower to real fixed-width WASM SIMD over the
`F64VEC`/`F32VEC` block; WITHOUT it (the DEFAULT) to plain scalar linear-memory loops with NO `0xFD`
opcode -- a v128-free MVP module that runs on a runtime lacking the SIMD proposal. The
`[count][data]` layout is byte-identical either way, so both compute the same result over the same
memory (element-wise bit for bit; reductions modulo summation order -- tests use exact inputs).
`--no-gc --simd` output is byte-identical to before the split.

`isSimdCall(name)` (a `"vec:"` prefix test) dispatches in all three passes: `collectCalls`
(`requireKnownSimd` + walk-args), `typeOf`/`typeOfSimd` (constructors -> the constructor width,
element-wise/`scale` -> the operand width, `length` -> `INT`, else `FLOAT`; UNCHANGED by `--simd`,
so inference matches either lowering), and `compileCall`/`compileSimd`. In v128 mode each kernel
branches on the inferred width (`packedVecType`).

- **Scalar (default)**: the four vectorizable kernels early-return to
  `compileScalar{Elementwise,Scale,Sum,Dot}`, which set up the same block (arg eval + `allocVec` +
  `dataPtr`) then drive a plain one-element-per-iteration loop (`openScalarCountLoop` + body +
  `closeSimdLoop`). f64 stays f64, f32 stays f32 (reductions accumulate in an `allocF32Local` then
  `f64.promote_f32` on return) -- the same per-width precision as v128. The
  `emitScalar{Map2,Scale,Sum,Dot}Loop` helpers take raw linear-memory locals and live in
  `WasmVecLoops` beside the `gc*` bodies. Here "scalar" means non-SIMD, distinct from the non-GC
  value model the compiler is named for.
- **`F64VEC` under `--simd`** (`f64x2`, 2 lanes/16 bytes): element-wise two lanes per iteration via
  `v128.load` + `f64x2.<op>` + `v128.store` (`openSimdLoop`/`closeSimdLoop` over
  `pairs = count >> 1`) plus a one-element scalar tail on odd length (`emitOddTailGuard`); `scale`
  is `f64x2.splat` + one mul per pair + tail; `sum`/`dot` accumulate in a v128 lane pair
  (`fn.allocV128Local()`) folded with `f64x2.extract_lane` 0/1 (`emitHorizontalAdd`) + odd tail.
- **`F32VEC` under `--simd`** (`f32x4`, 4 lanes/16 bytes): the same shape at half the stride
  (`openSimdLoop(..., laneShift=2)` over `quads = count >> 2`) with a scalar remainder LOOP over the
  last `count & 3` elements (`openScalarTailLoop`) instead of the odd-element guard. Scalars stay
  f64 at the value boundary (read `f64.promote_f32`, write/return `f32.demote_f64`) but every kernel
  computes ENTIRELY in f32 -- native `f32x4` + an `f32` scalar tail (`fn.allocF32Local()`), promoted
  on return. This matches llama2.c / a `FloatVector`'s f32-throughout semantics and diverges from
  the f64 oracle only for non-f32-exact operands, so tests use integer / power-of-two inputs. The
  `f64x2` path is byte-identical.
- **`matvec`/`matvec-into` run here** over a rank-2 packed matrix block: `Ty.F64MAT`/`F32MAT` is a
  distinct pointer kind to `[rows:i32][cols:i32][rows*cols f... row-major]` (the dims live in the
  block because this backend has no GC struct; the rank-1 layout is untouched). A rank-2
  `make-array` (`(list d n)` or `'(d n)`, parsed by `dimExprs`) builds it via `compileMakeMatrix`;
  two-subscript `aref`/`%aset` and flat `row-major-aref`/`%row-major-aset` index it
  (`emitMatElementAddr`); rank-2 literals, rank >= 3 and `length`/`vec:length` on a matrix stay
  clear compile errors. `compileSimdMatvec` runs one dot per row -- `WasmVecLoops.simdDot` under
  `--simd` (the f32 row accumulating in f32 lanes), `scalarDot` without -- restarting the row cursor
  from a maintained row pointer each iteration (the dot emitters clobber their pointer locals). The
  result is a fresh rank-1 vector of length `rows` in W's width; x (and out) must match W's width.
  `matvec-into` skips `allocVec` and traps (`unreachable`, pointer equality) when out aliases x or w.
- `zeros`/`ones`/`arange` are scalar fill loops; a literal `:element-type 'single-float` pair builds
  an `F32VEC` (`constructorVecType`), else `F64VEC`; the `collectCalls` arg-walk skips the keyword
  and the quoted designator, like `collectMakeArray`. `aref`/`aset`/`length` delegate to the shared
  packed helpers, width-aware via `elemShift(vecTy)` (f64 `<<3`, f32 `<<2`) and the load/store
  opcode. `mean`/`norm` expand to `(/ (sum) (length))` / `(sqrt (dot v v))` and recompile.
- Locals: `Fn.extraLocalTypes` is a `List<Integer>` of raw wasm value-type bytes (not `Ty`) so
  `allocV128Local()` can add `Type.V128` (0x7B) and `allocF32Local()` a bare `Type.F32` (0x7D),
  neither having a `Ty` kind; the body is emitted with `withLocalsRaw`. Constants (`SIMD_PREFIX`
  0xFD, `V128_LOAD`/`STORE`, `F64X2_*`, `F32X4_*` -- note `f32x4.extract_lane` is 0x1F, NOT 0x1B
  which is `i32x4.extract_lane`) live in `am.ik.wasm.Instruction`; sub-opcodes above 127
  (`f64x2.add` = 0xF0, `f32x4.add` = 0xE4) use the u32-LEB writer. A simd program flags the memory
  section via `usesFloatArray` (any `LispFloatArray` literal, `make-array`, or `vec:` call).
- wasmtime enables SIMD by default, so `--no-gc --simd` runs with a plain `wasmtime run`.
  Correctness alone no longer proves v128 ran (the scalar default is numerically equivalent), so the
  unit tests assert `0xFD` presence/absence directly.

## Layer 3 -- wasm-GC `--simd` native v128 over `(array (mut v128))`

`--simd` on the DEFAULT `.wasm` backend routes fifty-four kernels (the eight vectorizable, sixteen
unary ufuncs, four comparison selects and their twenty-six `-into` siblings; the operator aliases
reuse the add/sub/mul/div helpers) to emitted v128 runtime helpers.

The apparent blocker -- "`v128.load`/`store` address LINEAR memory, so a packed array must leave the
GC heap" -- is FALSE: the GC proposal's `fieldtype ::= storagetype ::= valtype | packedtype` and
`valtype` includes `vectype = v128`, so `(array (mut v128))` is a legal GC array and `array.get` on
it yields a v128. No `v128.load`, no arena, no `memory.grow`. `--simd` still changes the packed
representation, but to another collected object:

- `TYPE_FARRAY` is unchanged; `data` holds a `TYPE_VBLOCK = struct {i32 count, i32 kind,
  (ref null eq) groups}` instead of `TYPE_F64ARR`/`TYPE_F32ARR`, `groups` being
  `TYPE_V128ARR = (array (mut v128))`.
- `kind` 0 = f64 (2 lanes), 1 = f32 (4 lanes): the runtime width tag replacing `ref.test $f32arr`
  now that both widths share one array type. `count` is the logical element count.
- `groups` length is `ceil(count / lanes) + 1`. The `+1` is a ZERO SENTINEL GROUP so `matvec`'s
  shuffle window at the last group can always `array.get g+1` without a bounds trap.
- **No kernel has a scalar tail**: `array.new_default` zero-initializes and nothing writes past
  `count`, so the padding lanes are zero -- add/sub/mul/scale map 0 to 0 and sum/dot fold 0 in.
- Four new types, `--simd` ONLY, appended after `TYPE_F32ARR`: `TYPE_V128ARR`, `TYPE_VBLOCK`,
  `TYPE_V_GET` (`(eq,i32)->f64`), `TYPE_V_SET` (`(eq,i32,f64)->f64`). Declaring an
  `(array (mut v128))` at all requires the SIMD proposal, so the type must NOT appear in a default
  module -- which is what keeps the `simd=n` dead-flag guard working. Export/import wrapper type
  bases read `WasmLispCompiler.fixedTypeCount()` (`TYPE_F32ARR + 1 + (simd ? SIMD_TYPE_COUNT : 0)`),
  the conditional-index trick of `userFuncBase()`. A default module's type section is a strict
  PREFIX of a `--simd` one (`WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecFunctionBlock`),
  so component blobs are untouched.

Mechanics:

- **The kernels are standalone runtime functions**, not inline code: `WasmLispCompiler` declares
  every extra local of a compiled body as one `(ref null eq)` group, so a defun body cannot hold a
  v128/f64/i32/`(ref null $v128arr)` local. `WasmVecSimdRuntimeBuilder` hand-writes the local
  declarations (`withLocals(i32, f64, f32, v128, eq, v128arr)` -- that fixed ORDER is what all the
  index arithmetic assumes) per kernel. One kernel serves both widths by branching on `kind`; a
  mixed-width call traps (`requireSameKind`), matching the JVM bridge's hard error.
- **The one place the zero padding is not free is a WRITE.** A whole-group store reaches up to
  `lanes - 1` elements past `count` -- harmless when the destination is exactly `count` long, but an
  `-into` destination LONGER than its operands (which the contract allows) has REAL elements there
  that the defun leaves alone. `WasmVecLoops.gcSaveLastGroup`/`gcRestoreLastGroupTail` bracket the
  group loop and blend the last written group, restoring lanes `>= count % lanes` from the
  destination's pre-loop value (read BEFORE the loop, so an `out` aliased with an operand still sees
  its own pre-op lanes). Once per call. Side benefit: `(vec:scale v s)` with a non-finite `s` leaves
  zeros in the padding instead of NaN. Pinned by
  `wasmGcSimdIntoKernelsDoNotClobberADestinationLongerThanTheOperands`.
- **`_v_new`/`_v_get`/`_v_set`** (the first three emitted functions) own the width branch AND the
  immediate-lane branch: a lane index is an instruction immediate, so reading element `i` is
  `array.get (i >> laneShift)` then a 2-way (f64x2) or 4-way (f32x4) `if`-chain over `extract_lane`;
  a write is the same chain over `replace_lane` plus an `array.set`. Centralizing that keeps
  `WasmArrayCompiler`/`WasmQuoteCompiler`/`WasmRuntimeBuilder` at one `call` per aref/literal/print
  site. `_v_set` returns the value AS STORED (an f32 round-trip at single width), which is
  `emitPackedWriteF64`'s contract. `_v_new` reuses `TYPE_RAT_NEW`'s `(i32,i32)->(ref null eq)` shape.
- **`matvec` and the shuffle window**: row *r* of a *d x n* matrix starts at flat element `r*n`, in
  group `base = (r*n) >> laneShift` at lane `off = (r*n) & (lanes-1)`, both loop-invariant per row.
  `off == 0` reads one `array.get`; otherwise the row group is
  `i8x16.shuffle(groups[base+k], groups[base+k+1])` with immediate `[c, c+1, .. c+15]`,
  `c = off * elementBytes` (indices >= 16 naturally select the second operand). The immediate cannot
  be computed, so f64 emits 2 row-loop variants and f32 emits 4, selected by an `if`-chain on `off`
  once per row. Two facts make it safe: the sentinel group bounds the final `base+k+1`
  (`floor(x) + ceil(y) <= ceil(x+y)` puts the max index exactly at the sentinel), and the last row
  group's lanes that OVERHANG into the next row are multiplied by `x`'s ZERO PADDING.
  `matvec-into`'s destination may alias NEITHER `x` (each output element folds over all of it) NOR
  `W` (the row windows keep reading it), so the kernel `ref.eq`-traps against both -- matching
  `vec.lisp`, `JvmSimdVectorTemplate` and `VecSimd`, all three of which reject both. Pinned by
  `wasmGcSimdMatvecIntoRejectsADestinationAliasingEitherOperand`.
- **Function indices**: `FUNC_VEC_BASE = FUNC_WRITE_STR_GC + 1`, then `_v_new`/`_v_get`/`_v_set` +
  `_vec_add`..`_vec_cosh_into` (`FUNC_COUNT` = 47). Emitted ONLY under `--simd`, so `FUNC_USER_BASE`
  becomes dynamic (`WasmLispCompiler.userFuncBase()`, threaded via `Ctx.userFuncBase` into
  `WasmLambdaCompiler` and `WasmRuntimeBuilder.buildDispatchBody` -- the only three readers). Every
  fixed `FUNC_*` below it keeps its value and a non-`--simd` module is BYTE-IDENTICAL to a build
  that never knew about the flag.
- **Call-site interception**: `WasmVecSimdCompiler.handles/compile` in
  `WasmExprCompiler.compileCons`, gated on `ctx.simd` -- the shape of `JvmSimdCompiler`. `mean`/
  `norm` accelerate transitively; `#'vec:dot` still names the scalar defun, as on the JVM.
- **The rest of the packed surface** branches on `ctx.simd` at compile time (one module, one repr):
  `WasmArrayCompiler.compilePackedMakeVblock`/`emitPackedReadF64Vblock`/`emitPackedWriteF64Vblock`/
  `compileElementType`, `WasmQuoteCompiler`'s `#d`/`#f` literals (`compilePackedVblockLiteral` --
  one `v128.const` `array.set` per lane group, skipping all-zero groups),
  `WasmRuntimeBuilder.emitPrintArray` (count/kind from the vblock, elements via `_v_get`), and
  `WasmFloat16Compiler`'s `widen-float-bits`/`narrow-float-bits` destination and source
  (`Layout.VBLOCK`). `length`/`%arrayp`/`array-dimensions` read only `dims` and are untouched.
  `compilePackedMakeVblock` skips the fill loop when `:initial-element` is absent or a literal
  POSITIVE zero; `-0.0` is deliberately excluded (different bits).
- **Every writer of a packed array has to be on that list, and the way one gets missed is a test
  matrix counting BACKENDS rather than backends x `--simd`.** The bulk float-bits pair was added to
  four backends and pinned scalar-only; the wasm arm cast the destination's data field to
  `$f32arr`/`$f64arr`, a `ref.cast` TRAP the moment the field is a vblock, and it shipped green. A
  new primitive that reads or writes packed float data belongs in
  `WasmLispCompilerIntegrationTest` under BOTH values of `simd`.
- **Shared loop seam**: `WasmVecLoops` holds the four linear v128 bodies
  (`simdMap2`/`simdScale`/`simdSum`/`simdDot`), the four scalar ones, AND the four GC group bodies
  (`gcMap2`/`gcScale`/`gcSum`/`gcDot` over `openGroupLoop`/`closeGroupLoop`). `NoGcWasmCompiler`
  keeps delegating to the linear ones with its locals in the original order, so its output stays
  byte-identical.
- **Cost**: packed arrays remain ordinary GC objects and memory stays flat (a 5.6 GiB run of
  1048576-element `vec:add` allocations peaks at ~83 MB, more than a wasm32 linear memory can
  address). The GC representation costs ~1.93x on the kernel loop against a linear arena; the cause
  is `array.get`'s BOUNDS CHECK, which no engine hoists out of the loop. Typing the group locals
  `(ref $v128arr)` rather than `(ref null $v128arr)` removes the null check and buys nothing, so the
  nullable local stays. `--no-gc --simd` is the escape hatch; `--no-gc` is the only WASM target left
  with a never-freed arena.
- The scalar element helpers cost more in isolation (`_v_set`'s group read-modify-write is ~1.85x an
  `array.set`) -- invisible behind a BOXED loop, NOT behind a bare element loop: before the linalg
  interception, `linalg:add` (a flat `row-major-aref` loop `--simd` did not intercept) went
  205 ms -> 230 ms on wasm-GC when `--simd` switched the repr to a vblock, i.e. `--simd` was a 12%
  PESSIMIZATION for a linalg-only program. Fixed by intercepting the fifteen `linalg:` members; the
  cost is still real for what stays un-intercepted (`emap`, `inv` on wasm-GC), `.kb/linalg-simd.md`.
- Composes with `--optimize` (the shaker's `skipSimd` decodes 0xFD, incl. `v128.const` /
  `i8x16.shuffle`'s 16 immediate bytes and `replace_lane`'s lane byte) and `--component`.
- Tests: `WasmLispCompilerTest` (v128 local declarations present/absent -- the local decls are the
  one part of a code section that decodes without a full opcode walker, so an opcode-byte scan would
  false-positive; the default type section is a strict prefix and the four appended entries are
  asserted byte for byte; `FUNC_COUNT` delta; component/optimize compile).
  `WasmLispCompilerIntegrationTest` (Docker+wasmtime):
  `wasmGcSimdIsByteIdenticalToTheScalarPathOverTheWholeVecSurface` (both widths, every
  group-padding config, `-into`, GEMV, packed accessors, `make-array`, literals), `...Optimized...`,
  `wasmGcSimdMatvecMatchesTheScalarPathAtEveryRowLaneOffset` (all six shuffle variants),
  `wasmGcSimdPackedAccessorsMatchTheScalarPathAtEveryLane`,
  `wasmGcSimdPackedArraysAreCollectedRatherThanAccumulated` (the 5.6 GiB run -- it can only pass if
  the arrays are collected), `wasmGcSimdIntoKernelsReuseTheCallersDestination`, and the runnable
  dead-flag guard `wasmGcSimdModuleNeedsTheSimdProposalAndTheDefaultOneDoesNot`
  (`wasmtime --wasm simd=n --wasm relaxed-simd=n` refuses the `--simd` module -- failing at the TYPE
  section, not an opcode -- and runs the default one; relaxed-simd must be disabled too or wasmtime
  rejects the flag combination).

## The f32-reduction pinning probe -- the ONLY test that pins the precision contract

`v = #f(4096.0 1.0 ... 1.0)`, 1024 elements. `dot(v,v) = 4096^2 + 1023 = 16778239` exactly; `4096^2`
is `2^24`, where the f32 spacing is 2, so the lane holding it swallows every `1.0` (`2^24 + 1` ties
to even) while the other three lanes fold 256 ones each -- hence `2^24 + 768 = 16777984` under
`--simd`, on all four backends.

| probe | scalar (all backends) | `--simd` (all backends) |
|---|---|---|
| `(round (vec:dot v v))`, `v[0] = 4096.0` | 16778239 | **16777984** |
| `(round (vec:sum v))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (aref (vec:matvec m v) 0))`, 1x1024 | 16778240 | **16778176** |
| any of the above at `#d` width | 16778239 | 16778239 |

The GEMV row is no longer that chain: from `MATVEC_ACC_THRESHOLD = 2 * MATVEC_ACCUMULATORS * lanes
= 32` columns up, a row folds FOUR independent four-lane accumulators as `(a0 + a1) + (a2 + a3)`
into the single accumulator that then takes the leftover lane groups and the scalar tail, so the
1024-column probe groups as sixteen lanes and prints `2^24 + 960 = 16778176`. `vec:dot` and
`vec:sum` are UNCHANGED (one chain, four lanes): **a GEMV row and a `vec:dot` over the same two
vectors are the same value mathematically and NOT the same bits. Nothing may assume they agree.**
The scalar `matvec` prints 16778240, not 16778239: it accumulates in f64 and narrows on store, and
`2^24 + 1023` is an odd multiple of the f32 spacing there, so it ties to even.

Pinned three times -- `eval/VecSimdTest`, `codegen/jvm/JvmSimdAccelCompilerTest` (both
`singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`) and
`WasmLispCompilerIntegrationTest.wasmGcSimdSingleFloatReductionsAccumulateInSinglePrecision`.
NOTHING ELSE catches a regression here: every other `#f` test input stays under `2^24`, where an f32
accumulator is exact. `ci-spec.yaml` never passes `--simd`, so the cross-backend E2E is unaffected;
the component leg was verified by hand.

## Other verification

- `-into`: `VecSimdTest` (interception guard `#<function vec:add-into>`, each kernel vs its
  allocating sibling at n = 7 and 200, both widths, `(eq o (add-into o ...))`, in-place aliasing,
  `matvec-into` alias error on BOTH paths, mixed width); `JvmSimdAccelCompilerTest` (same +
  bridge-embedded dead-flag guard); `NoGcWasmCompilerTest` (`allocVec` site count 2 vs 3 under both
  lowerings -- matched on `i32.shl; i32.add; call $__ronto_alloc`, since a bare `0x10 <idx>` scan
  false-positives inside v128 immediates; `0xFD` presence/absence; f32 stride; `matvec-into` compile
  error); `WasmLispCompilerIntegrationTest.noGcRunsDestinationPassingVecKernelsUnderBothLowerings`;
  ci-spec `vec-destination-passing-kernels`.
- Unit: `JvmSimdAccelCompilerTest` (byte-identical to scalar over small scalar-tail and large
  vector-loop arrays, packed-surface interop, bridge gating; the GEMV set adds scalar-tail,
  n >= 128, concrete-value, width-preservation, mixed-width-error and dead-flag cases at both
  widths); `NoGcWasmCompilerTest` (`compileSimd()` = `NoGcWasmCompiler(false, true)` for the v128
  cases: `0xFD` presence for `f64x2` and `f32x4`, `#f` narrow/widen opcodes, plain-MVP shape,
  mixed-width + from-list compile errors; plus the two scalar cases via `compile()` asserting NO
  `0xFD` and the `f64.load/store` (0x2B/0x39) / `f32.load/store` (0x2A/0x38) opcodes).
- `--no-gc` end to end (Docker+wasmtime): `noGcRunsDoubleFloatVecKernelsWithF64x2Simd`,
  `noGcRunsSingleFloatVecKernelsWithF32x4Simd`,
  `noGcRunsVecKernelsScalarWithoutSimdMatchingTheV128Results` -- `wasmtime run --invoke` over
  int-returning `truncate` wrappers: `vec:dot`/`sum`/`scale`/`add` + `make-array` + `setf aref`
  across every tail config (0/1/3 leftover elements), matching the interpreter f64 oracle for
  integer inputs, both widths, both lowerings (the f32 v128 test also runs `--optimize`). This
  surfaced and fixed a `WasmTreeShaker` gap -- no case for the `0xFD` prefix, so `--no-gc
  --optimize` on ANY vec program threw "unhandled opcode 0xFD".
- Cross-backend: ci-spec `vec-kernels-cross-backend` (interpreter / JVM / WASM P1 / component
  byte-identical; f64-exact inputs so `mean`/`norm` land on exact doubles, plus two `vec:matvec`
  lines -- a square and a non-square `#d` matrix -> `#d(17.0 39.0)` / `#d(14.0 32.0)`).
  `examples/ml/nn-vec.lisp` runs on interpreter/JVM/wasm via `ExamplesE2eTest`. Run the native
  `CiSpecE2eTest` after editing it.
- Manual `--no-gc`: `wasmtime run --invoke <fn> module.wasm <args>` (result on stderr; filter
  `^warning:`).

## Writing a `--simd` example or benchmark

Everything about how a particular JVM behaves is OUR measurement, not vendor-documented behavior: it
may live here, never in `doc/**` or an example header.

- **`THRESHOLD = 128` is compared against the ROW LENGTH**, not the total element count, and the
  GEMV row loops have their own gate `MATVEC_ROW_THRESHOLD = 16` (`matvecRows`/`matvecRowsF` in
  `JvmSimdVectorTemplate` and `eval.VecSimdKernels`, the same value in both): a GEMV amortizes its
  per-call setup over every row, so a row only needs one lane chain's worth of elements, and under
  the 128 gate a matrix of many short rows (llama2's attention over a 48-wide head, 256 rows, 72
  calls a token) ran every row scalar. Below 16 per row, and below 128 for the element-wise kernels
  and other reductions, interpreter and JVM run a scalar loop; wasm-GC and `--no-gc` have no
  threshold. `nn-vec.lisp`'s rows of 2 and 4 see nothing from `--simd`; `tiny-llm.lisp`'s
  `(vec:matvec vt a)` (row length = the CONTEXT length) stays scalar until a real context passes 16
  tokens.
- **Print only INTEGERS.** WASM prints floats to ~7 significant digits and its `exp` differs from
  the JVM's in the low bits, so a float never compares across backends. `argmax` is the trick: an
  integer that depends on every multiply-add yet is unmoved by lane-order rounding.
- `get-internal-real-time` is in MILLISECONDS -- an INTEGER on interpreter/JVM, a FLOAT on WASM --
  and `internal-time-units-per-second` does NOT exist, so an elapsed-time line must never be checked
  by `examples.yaml`.
- `--no-gc` cannot compile `linalg:` at all (`&optional` in `linalg::%la-make`) and rejects
  `vec:matvec`, so any example using either is `[interpreter, jvm, wasm]` only.
- Interpreter time budget for `ExamplesE2eTest`: heat3d 0.0 s .. simd-gemv 4.7 s .. deep-digits
  10.1 s .. tiny-llm ~13 s .. mlp 38.4 s. `ExamplesE2eTest` can fail spuriously when the GraalVM JIT
  prints a "Systemic Graal compilation failure" warning onto the program's stdout -- re-run.
- **Benchmarking discipline.** Run benchmarks SEQUENTIALLY (a parallel subagent corrupts the
  numbers). Take N >= 9 samples and print them ALL before claiming two configurations differ (a
  GraalVM scalar timing turned out bimodal -- `226 269 269 271 271 381 383 395 400` -- where a
  median would have hidden it). Raise the iteration count until the JIT reaches steady state.
  Measure allocation with `-XX:+UseEpsilonGC -Xmx12g -Xlog:gc` and read heap-used-at-exit (the GC
  counts are useless). zsh does NOT word-split an unquoted `$FLAGS` -- write JVM flags inline.
- **Vary the axis you are not thinking about.** Five experiments "confirmed" GraalVM cannot
  vectorize because every one used `#f`; the first `#d` program came out 1100x faster on the
  interpreter and destroyed the hypothesis. The cause was one un-intrinsified op in the `#f`
  reduction kernels.

## Names / registration

`LispNames.VEC_PKG` + `VEC_ZEROS`..`VEC_NORM`/`VEC_MATVEC` (+ `VEC_QUALIFIED_AREF`/`_ASET`);
`PackageRegistry.VEC_FUNCTIONS` (external, no `cl` use) + `vecFunctionNames()`. Native image:
`resource-config.json` registers `vec.lisp` (VecLibrary) and `JvmSimdVectorTemplate.class`
(JvmSimdRuntimeBuilder).

## Not done / follow-ups

- `linalg:` acceleration is DONE: fifteen members intercepted on the interpreter, the JVM and
  wasm-GC, reusing these lane loops. One structural difference -- a linalg kernel is PARTIAL (it
  declines general arrays, mixed widths, plain numbers and shape errors by returning null and the
  call site runs the scalar defun), because unlike `vec:` the linalg defuns accept all of those.
  `.kb/linalg-simd.md`; the width polymorphism is `.kb/linalg.md`.
- Full matrix x matrix GEMM (`matmul`) is not `vec:` (it produces a matrix); it lives in `linalg:`.
  It needs no transpose: rewriting the oracle's `ijk` triple loop as `ikj` makes `b`'s rows
  contiguous AND preserves the summation order, so at `#d` the result is bit-identical rather than
  merely close; at `#f` it follows the single-precision reduction contract.
- `--no-gc` GEMV is DONE (layer 2). Still out of scope there: rank-2 `#d`/`#f` literals, rank >= 3,
  `array-dimensions`/`array-dimension` on the matrix (the former needs cons lists), and general
  (non-packed) rank-2 arrays.
- A stories15M-scale llama2 demo with real weights. `examples/ml/tiny-llm.lisp` is the
  real-transformer payoff at toy scale (a 2-layer decoder, 13 GEMVs per forward pass, deterministic
  token ids); what is left is a tokenizer and a weight loader.
