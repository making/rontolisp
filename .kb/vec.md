# vec: package + packed float-array acceleration

The `vec:` package is a set of portable packed-`f64` vector kernels layered on the
dedicated **packed float-array type** (see the packed float-array constraint in
`CLAUDE.md` and `.todo/94`). This file covers the `vec:` package and the two
acceleration layers; the packed representation itself lives in `LispFloatArray`,
`JvmFloatArrayRuntimeBuilder`, `WasmArrayCompiler` (the `$farray` struct) and
`NoGcWasmCompiler` (`F64VEC`).

## The type it rides on

A vector is a rank-1 packed `(array double-float)` — the same unboxed-double array
that `#d(...)` and `(make-array n :element-type 'double-float)` produce, so the generic
`aref` / `(setf aref)` / `length` / `make-array` interoperate on every backend. Element
type is `double-float`: storing a non-real is a type error, and there is no boxed/general
fallback for a packed array (the todo-92 shadow/degrade path is gone). The single-float
sibling `#f(...)` / `:element-type 'single-float` is a *different* width (todo-95); `vec:`
is **width-polymorphic** and rides on both — the element-wise kernels preserve the input
width on every backend (`vec::%make-like`), and the reductions fold to an f64 scalar.

Per-backend repr: interpreter `record LispDoubleFloatArray(double[] data, int[] dims)`
(one width of the sealed `LispFloatArray` umbrella, `LispSingleFloatArray(float[])` being
the other); JVM a bare `double[]` with an embedded `[rank, dim..., data...]` header (data
offset `1 + rank`, so a rank-1 vector is `[1.0, n, e0..]`; single-float is a bare
`float[]` with the same header); wasm-GC a distinct `TYPE_FARRAY` struct whose data field
holds a `TYPE_F64ARR = (array (mut f64))` (double) or, for `#f` single-float, a
`TYPE_F32ARR = (array (mut f32))` — the same struct, width told apart by
`ref.test $f32arr` (todo-95 Phase 4) -- **except under `--simd`**, where the data field
instead holds a `TYPE_VBLOCK` over an `(array (mut v128))` of lane groups (still a GC
object; acceleration layer 3 below; the struct type is unchanged, since the field is
`(ref null eq)` either way, todo-105); `--no-gc` a `[count:i32][count f64]` (`Ty.F64VEC`,
8-byte stride) or `[count:i32][count f32]` (`Ty.F32VEC`, 4-byte stride) linear-memory
block, keyed off the `#d`/`#f` literal or the `:element-type` (todo-95 Phase 5).

## vec.lisp = the scalar reference / cross-backend oracle

`src/main/resources/am/ik/rontolisp/eval/vec.lisp` defines every `vec:` function as a
plain `defun` over `make-array :element-type 'double-float` / `aref` / `length`. It is the
implementation on the interpreter (unless `--simd` — see acceleration layer 0), the JVM
compiler and the wasm-GC compiler (they run the scalar defuns over the packed repr,
unboxed; a `--simd` build of either intercepts the seven vectorizable kernels at their
call sites and leaves the rest of the defuns in place), and the correctness oracle for the
accelerated paths. `VecLibrary` splices/loads
it exactly like `LinalgLibrary`:

- Interpreter: `LispEvaluator` lazy-loads it on the first resolution of a `vec:`-qualified
  function (`VecLibrary.forms()`), mirroring linalg.
- JVM / wasm-GC compile path: `RontoLispCli` (and `RontoPlayground`, the corpus/e2e test
  helpers) call `VecLibrary.process(program)` after user-macro expansion.
- **`--no-gc` is gated OFF** the splice (`RontoLispCli`: `!(outputFile.endsWith(".wasm") &&
  noGc)`): it has no general array type and intercepts the whole `vec:` surface natively.

Members: `zeros`/`ones`/`arange`/`from-list`/`to-list` (construction; `zeros`/`ones`/
`arange` take an optional trailing `element-type` — a literal `'single-float` builds `#f`,
else the double default — through the `vec::%make` funnel, mirroring the linalg constructors),
`aref`/`aset`/
`length` (thin wrappers), `add`/`sub`/`mul`/`scale` (element-wise, fresh vector), the
unary ufuncs `exp`/`sqrt`/`abs`/`square`/`negative`/`sign`/`reciprocal` (element-wise,
fresh vector, numpy names -- todo 109; see "Element-wise unary ufuncs" below), `sum`/
`dot`/`mean`/`norm` (reductions, scalar), `matvec` (GEMV — a rank-2 matrix × a rank-1
vector → a fresh rank-1 vector, todo-95 Part 2; the scalar defun reads `(aref w i j)` over
`(array-dimensions w)` and allocates via `vec::%make-like`). `from-list`/`to-list` need cons
lists, so they are portable-backends-only (a `--no-gc` compile error); `matvec` needs a
rank-2 matrix, so it is a `--no-gc` compile error too. `(setf (vec:aref v i) x)` →
`(vec:aset v i x)` via `LispMacroExpander.expandSetf` (`VEC_QUALIFIED_AREF`).

## Destination-passing `-into` kernels (todo-103)

Each vector-returning kernel has an `-into` sibling — `add-into`/`sub-into`/`mul-into`/
`scale-into`/`matvec-into`, plus the unary `exp-into`..`reciprocal-into` (todo 109) —
that writes into a caller-supplied destination (argument 1, CL's `map-into` order) and
RETURNS that very value. A unary `-into` destination MAY alias the operand (element i
depends only on element i, the add-into rule). Reductions have none (they never
allocated).

**Rationale, and exactly which backend leaks.** A packed array is a GC object on the
interpreter (`double[]` in a `LispFloatArray`), the JVM (a bare `double[]`) and **wasm-GC
without `--simd`** (`array.new $f64arr` inside `$farray`) and **wasm-GC WITH `--simd`**
(`array.new_default $v128arr` inside `$vblock`, todo-105) — all four reclaim it. Only
`--no-gc` puts it in linear memory, bump-allocated with **no free**, so an allocating
kernel in a loop grows the heap monotonically there. (Measured: 1024-element `vec:add` ×
200000 on scalar wasm-GC peaks at ~123 MB, same as × 50000, though 1.5 GB passed through
the allocator; `linalg:add` × 80000 likewise flat; under `--simd`, 700 × 1048576-element
`vec:add` — 5.6 GiB, more than a wasm32 linear memory can address — peaks at ~83 MB.)
`--no-gc` reclaims by
popping the whole arena at an export boundary (todo-88 auto-reset on a scalar return,
todo-89 `__ronto_alloc_mark`/`_reset` for a host) — nothing is freed *within* one call.
`-into` makes the bump high-water equal the live set, which is all a GC would have kept
anyway, so freeing becomes an optimization rather than a correctness requirement
(`.todo/104` — `with-arena`, a mark/reset of `--no-gc`'s one bump word). On the three GC'd
backends — including wasm-GC `--simd` since todo-105 — `-into` is purely an allocation-rate
optimization. `linalg` arrays are the same packed type and behave identically (`linalg` does
not compile under `--no-gc` at all).

- **Aliasing.** The element-wise kernels tolerate `out` aliasing `a` and/or `b` (element
  `i` depends only on element `i`; within one lane block the reads precede the store), so
  `(vec:add-into acc acc d)` is the intended in-place accumulation. `matvec-into` does NOT:
  `out[row]` folds over all of `x`. The guard is an `eq` in the `vec.lisp` defun AND is
  repeated in `VecSimd` / `JvmSimdVectorTemplate` — the accelerated call sites REPLACE the
  defun, so the defun's guard never runs there. Interpreter/JVM compare the BACKING array
  (`r.data() == vx.data()`), the real sharing condition.
- **Widths** must match across `out` and the operands (a mixed call is the usual hard
  error). `out`'s length is NOT checked, matching `vec:add`'s existing behavior on
  unequal-length operands.
- Per backend: `vec.lisp` covers interpreter/JVM/wasm-GC for free. `eval/VecSimdKernels`
  (`addInto`..`matvecIntoF`) + `VecSimd.installInto` accelerate the interpreter;
  `JvmSimdVectorTemplate.simdAddInto`..`simdMatvecInto` + `JvmSimdCompiler.ARITIES` (arity 3,
  a `ternaryDesc` in `JvmSimdRuntimeBuilder`) the JVM; `WasmVecSimdRuntimeBuilder`'s
  `_vec_add_into`.._vec_matvec_into` (a `boolean into` through `buildElementwise`/
  `buildScale`/`buildMatvec`, which then use the caller's block as the destination and
  return argument 0) the wasm-GC `--simd` path. `NoGcWasmCompiler` threads a
  `boolean into` through `compileSimd/ScalarElementwise{,F32}` and
  `compileSimd/ScalarScale{,F32}`: `-into` skips `allocVec` and uses the caller's block as
  `dp` (via the new `compileVecArg`/`loadVecCount` helpers), so the loop bodies are literally
  unchanged. `matvec-into` joins `matvec` in `SIMD_UNSUPPORTED_NO_GC` (rank-1 blocks only).
- **Measured** (`--no-gc --simd`, 12000 accumulations over a 65536-element vector, macOS
  `/usr/bin/time -l`): `vec:add-into` peaks at 13.7 MB and returns 12000; `vec:add` peaks at
  4.31 GB and then traps (`memory.grow` fails, so the store goes out of bounds). This is the
  property the feature exists to buy; `NoGcWasmCompilerTest.intoKernelsCallTheBumpAllocator`
  `OnlyForTheConstructors` pins it structurally (2 `allocVec` sites vs 3).

## Element-wise unary ufuncs (todo 109 Phase 1)

`exp`/`sqrt`/`abs`/`square`/`negative`/`sign`/`reciprocal` (+ `-into` siblings) exist in
BOTH packages under their numpy ufunc names. The design decisions, per backend:

- **The oracle is each backend's OWN scalar defun** (the emap rule: read widened to f64,
  apply the backend's scalar op, narrow on store). The scalar ops' edges already diverge
  across backends -- interpreter/JVM use `Math.exp/sqrt/abs/signum` and true negation,
  while wasm's variable-path `abs` is `x < 0 ? 0 - x : x` (keeps `-0.0`), its unary
  minus is `0 - x` (`(- 0.0)` is `0.0`), its `signum` maps `-0.0`/NaN to `0.0`, and its
  `exp` is the `WasmExpCompiler` software approximation (todo-108 residuals). So each
  `--simd` kernel mirrors ITS backend's defun, per-backend bit-identity holds, and
  cross-backend `-0.0`/NaN/exp-low-digit output stays out of ci-spec.
- **Lane forms only where they equal the defun**: interpreter/JVM lane-ize sqrt (SQRT,
  correctly rounded), abs (ABS), negative (NEG) and reciprocal (broadcast(1)/v); exp and
  sign stay de-boxed scalar loops (`VectorOperators.EXP` is NOT bit-identical to
  `Math.exp`). wasm-GC lane-izes sqrt (`f64x2/f32x4.sqrt`), negative (sub-from-splat-0),
  reciprocal (div-from-splat-1) and abs (`bitselect(0 - v, v, v < 0)` -- NOT
  `f64x2.abs`, which would map `-0.0` to `0.0` and diverge from the wasm defun); exp and
  sign walk `_v_get`/`_v_set` element loops emitting the defun's exact f64 sequence
  (`WasmExpCompiler`'s constants are package-private for that). All f32 lane forms are
  exact by the `53 >= 2*24+2` bound or correct rounding, so `#f` results equal the
  widen-compute-narrow defun bit-for-bit.
- **`square` and `reciprocal` ride existing kernels where a defun exists**: `vec:square`
  = `(vec:mul v v)`, `linalg:square` = `(linalg:mul a a)`, `linalg:reciprocal` =
  `(linalg:div 1 a)` -- accelerated transitively, never intercepted (the interception
  guards pin `#'vec:square` as `#<lambda>`). `vec:reciprocal` has its own kernel (vec:
  has no div).
- **`--no-gc`**: the five arithmetic ufuncs (+`-into`) lower natively --
  `WasmVecLoops.simdMap1`/`scalarMap1` over the linear block, native IEEE `f64.abs`/
  `f64.neg` semantics since there is no defun to mirror there. `vec:exp` / `vec:sign`
  (+`-into`) lower natively too (Phase 1.5, 2026-07-10; they were decision-(b) compile
  errors in Phase 1): `NoGcWasmCompiler.compileSimdUnaryF64` drives a one-element-per-
  iteration loop over the SAME raw-f64 emitters the wasm-GC `--simd` kernels use
  (`WasmVecSimdRuntimeBuilder.emitExpF64`/`emitSignumF64` -- the `WasmExpCompiler`
  software approximation and the `(x>0)-(x<0)` sign), an f32 element widening on read
  and narrowing on store (the emap rule). exp has no lane form anywhere and sign's is
  not worth one, so BOTH `--simd` modes emit the identical loop (no `0xFD`); values
  equal the wasm-GC backend's exactly and diverge from interpreter/JVM at the same
  edges the wasm scalar builtins already do (exp low digits; sign maps `-0.0`/NaN to
  `0.0`). The scalar `(exp x)`/`(signum x)` builtins themselves remain unknown on
  `--no-gc` (only the `vec:` kernels gained the lowering).
- New v128 opcodes for all this (`f32x4/f64x2.sqrt/abs/neg/lt`, `v128.bitselect`) are in
  `am.ik.wasm.Instruction` AND `WasmTreeShaker.skipSimd` (which throws on unknown 0xFD).

Pinned by the unary-ufunc test blocks in `eval/VecSimdTest`, `eval/LinalgSimdTest`,
`JvmSimdAccelCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `NoGcWasmCompilerTest`
(incl. `expAndSignLowerNativelyOnNoGc`: INV_SCALE constant present, no `0xFD` in either
mode, `-into` skips the allocator) and `WasmLispCompilerIntegrationTest`
(`wasmGcSimdUnaryUfuncsAreByteIdenticalToTheScalarPath`,
`wasmGcSimdLinalgUnaryUfuncsAreByteIdenticalToTheScalarPath`,
`noGcRunsUnaryUfuncsUnderBothLowerings`, `noGcRunsExpAndSignUnderBothLowerings` --
the nontrivial exp probes compare a `--no-gc` run against a wasm-GC run, not a
hardcoded constant).

## Acceleration layer 0 — interpreter `--simd` (jdk.incubator.vector), opt-in

`rontolisp prog.lisp --simd` (interpret, no `-o`) runs the same seven vectorizable kernels
on the Vector API instead of the scalar defuns. The DEFAULT interpreter is unchanged — it
is the cross-backend byte-identity oracle, and `ci-spec.yaml` never passes `--simd`.

- `eval.VecSimdKernels` — the lane loops, `static`, over the interpreter's **bare**
  `double[]`/`float[]` + explicit `rows`/`cols` (a `LispDoubleFloatArray`/`LispSingleFloatArray`
  has no in-array dimension header, unlike the compiled repr). Mirrors
  `JvmSimdVectorTemplate` operation for operation (same `SPECIES_PREFERRED` for the
  element-wise kernels, same `FSPECIES_REDUCE = FloatVector.SPECIES_128` pin on the f32
  reductions, `THRESHOLD = 128`, two-rounding mul-then-add, f32-throughout f32 reductions,
  f64-then-narrow `scaleF`), so interpreter `--simd` ≡ compiled `.class --simd` bit-for-bit.
  It is NOT reused from `codegen.jvm` — `eval` may not depend on it (package rule), and the
  template's kernels are written against the header-in-array layout anyway.
- **The f32 reductions are conversion-free (todo-106, 2026-07-09).** `sumF`/`dotF`/`matvecF`/
  `matvecIntoF` used to widen every f32 lane to f64 via `FloatVector.convert(F2D, part)` before
  accumulating, to stay bit-identical to the f64-accumulating scalar oracle. That widening was a
  liability on two counts: it is the Vector-API op most likely to be missing from a JIT's
  intrinsics (one compiler family emulates it lane by lane), and it is never free even when
  intrinsified. And it bought a bit-identity the **WASM `--simd` kernels never honoured**
  (`WasmVecLoops`: "each width computes entirely in its own native precision"). Now every
  `--simd` backend accumulates an f32 reduction in f32 and promotes once, at the value boundary,
  so all four agree; the scalar `vec.lisp` reference stays the more accurate oracle and is
  unchanged. **`#d` is untouched** (`DoubleVector`, `SPECIES_PREFERRED`, f64 accumulator).
  Measured on an M4, `vec:dot` over 40.96M multiply-adds through the interpreter kernels:

  | runtime | `#d` | `#f` before | `#f` after |
  |---|---|---|---|
  | native binary | 30 ms | 4149 ms | **23 ms** |
  | GraalVM JIT (jar) | 22 ms | 2250 ms | **18 ms** |
  | Liberica 25 / HotSpot (jar) | 21 ms | 23 ms | **15 ms** |

  `#f` is now FASTER than `#d` everywhere (4 lanes vs 2), which is the point of `f32x4`; the
  compiled-`.class` bridge lands at 4-5 ms on both JVMs. `examples/ml/tiny-llm.lisp` decode on the
  native interpreter: 1669 ms → **20 ms**; `simd-gemv.lisp`: 673 ms → **15 ms**.
- **The lane-count pin.** An f32 reduction's value depends on the lane count (`2^24 + 768` with 4
  lanes, `+ 896` with 8, `+ 960` with 16), so `FSPECIES_REDUCE` is `SPECIES_128` rather than
  `SPECIES_PREFERRED` in BOTH kernel files: a compiled `.class` / native binary must not answer
  differently on an AVX-512 host, and the WASM kernels are always `f32x4`. The element-wise f32
  kernels keep `SPECIES_PREFERRED` — they are bit-exact at any width. (The f64 reductions still use
  `SPECIES_PREFERRED`; their lane count also reorders the summation, but that was true before and
  `#d` partial sums are exact on the inputs the tests use.)
- `eval.VecSimd` — `available()` (links the kernels class; a `NoClassDefFoundError` on a JVM
  without the incubator module becomes `false`) and `install(Environment)` (defines native
  `LispFunction`s for `vec:add`..`vec:matvec`, overriding the just-evaluated defuns).
  `mean`/`norm` keep their scalar bodies and pick the natives up through the Lisp-2 global
  function namespace. These two methods are the ONLY callers of `VecSimdKernels`.
- `LispEvaluator.setSimd(true)` → `VecSimd.install(globalEnv)` right after the `vec.lisp`
  forms are evaluated in `resolveFunction`'s lazy-load hook.
- `RontoLispCli.interpret` threads `--simd`, probing `VecSimd.available()` first: absent
  module → a one-line note + the scalar reference (a graceful fallback, unlike the compiled
  `.class`'s hard `NoClassDefFoundError` dead-flag guard). `--simd` in the REPL warns.
- **Native binary**: the `native` profile passes `--add-modules jdk.incubator.vector` +
  `-H:+VectorAPISupport` (build-time only; the binary needs no runtime flag). Without the
  latter the Vector API falls back to per-lane emulation 6-32x SLOWER than scalar, so it is
  effectively mandatory. **GraalVM 25 refuses to combine it with `-H:+SharedArenaSupport`**,
  which the JLine FFM terminal provider needs (it closes an `Arena.ofShared` from its signal
  handler on REPL shutdown) — so `JLineRepl.selectNativeImageTerminalProvider()` pins
  `org.jline.terminal.provider=jni` in the image instead (no shared arena) and the pom drops
  `SharedArenaSupport`. Forcing `-Dorg.jline.terminal.provider=ffm` on the binary reproduces
  the old crash; that is the pinning test.
- **Web Image**: `src/web/java/.../Target_VecSimd.java` substitutes both `available()` (→
  `false`) and `install(...)`, making `VecSimdKernels` unreachable so the incubator module
  never enters the browser image. What makes the substitution sufficient is that those two
  are the only ENTRY POINTS into the kernels — the `-into` kernel references (todo-103) sit
  in the private `installInto`/`defineInto`, reachable only from the substituted `install`.
  A new PUBLIC `VecSimd` method touching the kernels would break it, and only the Pages
  workflow's Web Image build would notice ([[web-playground-native-image-gotcha]]).
- Tests: `eval/VecSimdTest` (every kernel vs the scalar oracle at both widths, below and
  above `THRESHOLD`; the `#<function vec:dot>` vs `#<lambda>` interception guard; mixed-width
  and rank errors) plus `singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`, the
  todo-106 pinning probe (see "The f32-reduction pinning probe" below).

## Acceleration layer 1 — JVM `--simd` (jdk.incubator.vector)

`--simd` routes the **seven vectorizable kernels** (`add`/`sub`/`mul`/`scale`/`dot`/`sum`/
`matvec`) at their call sites to an embedded `jdk.incubator.vector` bridge, replacing the
scalar defun. `mean`/`norm` are accelerated transitively (their spliced bodies call
`sum`/`dot`).

- `JvmSimdVectorTemplate` — the Vector-API kernels (plain Java, compiled by the project;
  the pom adds `--add-modules jdk.incubator.vector` to javac + surefire). Unbox is
  **trivial and zero-copy** in the packed design: cast `(double[]) arg`, use `off = 1 +
  (int)arg[0]`, read via `DoubleVector.fromArray(SPECIES, arr, off + i)`; the result is a
  fresh packed `double[]` (`[1.0, n, ...]`). No shadow logic. `THRESHOLD = 128` gates the
  lane loop vs a scalar loop; the dot two-rounding mul-then-add (not fma) keeps the only
  scalar-vs-vector divergence to reduction associativity.
- **Width-polymorphic bridge:** each kernel dispatches on the runtime backing — a `double[]`
  runs the `DoubleVector` path, a `float[]` the sibling `FloatVector` kernel (element-wise
  → a fresh `float[]`, width preserved; `sum`/`dot` → an f64 scalar, accumulated in f32 over
  four pinned lanes and promoted once on return — todo-106). Mixed single/double operands are
  a hard `IllegalArgumentException`.
- **`simdMatvec` (GEMV, todo-95 Part 2):** the `simdDot` lane loop run once per row of a
  rank-2 matrix `W` — header `[2, d, n, ...]` so `ow = 1 + (int)W[0] = 3`, `d = (int)W[1]`
  rows, `n = (int)W[2]` cols; `r[2 + row] = dot(W row, x)` into a fresh length-`d` vector.
  ONE bridge call covers the whole matrix (amortizing entry over `d` rows). Width-polymorphic
  like the rest (`matvecF` mirrors `dotF`, each row's f32 acc stored straight into the
  `float[]`).
- `JvmSimdRuntimeBuilder` — reads the template `.class` from the classpath, renames it into
  the default package (`RontoLispSimdBridge`), base64-embeds it, and emits `_simdInit`
  (a `Lookup.defineClass` guarded by `_simdInited`), exactly like the `java:` interop bridge.
- `JvmSimdCompiler.compile` — emits the call site: `_simdInit` then the args then the bridge
  method. Wired in `JvmExprCompiler` (`ctx.simdOps != null && JvmSimdCompiler.handles(...)`).
- Gate: `JvmLispCompiler` computes `usesSimd = simdAccel && programUsesAnyAcceleratedSimdOp`
  and builds the runtime → `Ctx.simdOps`. **`JvmArrayRuntimeBuilder` / the `_fv*` packed
  helpers are UNCHANGED** — packed is a separate repr; the bridge result is rendered/indexed
  by the same `_fv*` helpers. Running a `--simd` class needs
  `java --add-modules jdk.incubator.vector`; the default build is byte-identical and needs
  no incubator module.
- **Dead-flag guard** ([[simd-shadow-and-dead-flag-lesson]]): running a `--simd` class
  without `--add-modules jdk.incubator.vector` MUST fail at `_simdInit`'s `defineClass`
  (`NoClassDefFoundError: jdk/incubator/vector/Vector`) — proof the interception fired. A
  scalar build runs fine without the module.
- Because the spliced `mean`/`norm` bodies always call `sum`/`dot`, ANY `--simd` program
  using the vec package at all embeds the bridge (the dead defuns are shaken by `--optimize`).

## Acceleration layer 2 — `--no-gc` `--simd` native v128 (`f64x2.*` / `f32x4.*`); scalar loops by default

`NoGcWasmCompiler` lowers the whole `vec:` surface itself (vec.lisp is not spliced under
`--no-gc`). **`--simd` (ctor `this.simd`) is the switch**: with it the vectorizable kernels
lower to real fixed-width WASM SIMD over the `F64VEC`/`F32VEC` block; WITHOUT it (the
DEFAULT) to plain scalar linear-memory loops with NO `0xFD` opcode — a v128-free MVP module
that runs on a runtime lacking the SIMD proposal (`.todo/100`; before todo-100 `--no-gc`
ALWAYS emitted v128 and `--simd` was silently JVM-only — this is the behavioral change). The
`[count][data]` block layout is byte-identical either way, so the two compute the same
result over the same memory (element-wise bit-for-bit; reductions modulo summation order —
tests use exact inputs). The four vectorizable kernels (`compileSimdElementwise`/`Scale`/
`Sum`/`Dot`) early-return to a `compileScalar*` seam when `this.simd` is false, and the
todo-109 arithmetic unary ufuncs (`compileSimdUnary` over `WasmVecLoops.simdMap1`/
`scalarMap1`) branch the same way; the v128
code is left untouched (so `--no-gc --simd` output is byte-identical to the pre-todo-100
`--no-gc` output). `vec:exp`/`vec:sign` (+`-into`) are the exception to the mode branch:
`compileSimdUnaryF64` emits the SAME element loop in both modes (no lane form exists;
raw-f64 emitters shared with the wasm-GC kernels -- see the todo-109 section above). `isSimdCall(name)` (a `"vec:"` prefix test) dispatches in all three
passes: `collectCalls` (eligibility: `requireKnownSimd` + walk-args), `typeOf`/`typeOfSimd`
(constructors → the constructor width, element-wise/`scale` → the operand width, `length` →
`INT`, else `FLOAT`; UNCHANGED by `--simd`, so inference matches either lowering), and
`compileCall`/`compileSimd`. In v128 mode each kernel branches on the operand's inferred
width (`packedVecType`): `F64VEC` → the byte-identical `f64x2` path, `F32VEC` → the `f32x4`
sibling. **`matvec` (GEMV) is the one `vec:` member `--no-gc` rejects** (both modes) — a
`--no-gc` packed vector is a rank-1 `[count][f...]` linear block with no rank-2 layout to
read rows from, so `requireKnownSimd` throws via `SIMD_UNSUPPORTED_NO_GC` with a clear error
pointing to the JVM `--simd` (or interpreter/JVM/wasm-GC scalar) path.

- **Scalar (no `--simd`, the DEFAULT):** the four vectorizable kernels early-return to
  `compileScalar{Elementwise,Scale,Sum,Dot}`, which set up the same block (arg eval +
  `allocVec` + `dataPtr`) then drive a plain one-element-per-iteration loop
  (`openScalarCountLoop` + body + `closeSimdLoop`, no `0xFD`). f64 stays f64
  (`f64.load`/op/`f64.store`), f32 stays f32 (`f32.load`/op/`f32.store`; reductions
  accumulate in an `allocF32Local` then `f64.promote_f32` on return) — the same per-width
  precision as the v128 path, so numerically equivalent on exact inputs. `zeros`/`ones`/
  `arange`/`aref`/`aset`/`length` are already scalar (no v128), so they are unaffected by the
  switch. **Shared seam:** the `emitScalar{Map2,Scale,Sum,Dot}Loop` helpers take raw
  linear-memory locals (data pointer past the `[count]` header + element count + width) and
  live in `WasmVecLoops`, next to the `gc*` bodies the wasm-GC `--simd` kernels drive over
  lane groups. Here "scalar" means non-SIMD, distinct from the non-GC value model the
  compiler is named for.
- **`F64VEC` under `--simd` (double, `f64x2` = 2 lanes/16 bytes):** element-wise (`add`/`sub`/`mul`) two
  f64 lanes per iteration via `v128.load` + `f64x2.<op>` + `v128.store`
  (`openSimdLoop`/`closeSimdLoop` over `pairs = count >> 1`), plus a one-element scalar tail
  when the length is odd (`emitOddTailGuard`). `scale`: `f64x2.splat` the scalar, one
  `f64x2.mul` per pair + tail. `sum`/`dot`: accumulate in a **v128 lane pair**
  (`fn.allocV128Local()`), fold with `f64x2.extract_lane` 0/1 (`emitHorizontalAdd`), plus
  the odd tail. Computes entirely in f64 (its native width).
- **`F32VEC` under `--simd` (single, `f32x4` = 4 lanes/16 bytes; todo-95 Phase 5):** the same shape at
  half the stride — FOUR f32 lanes per iteration (`openSimdLoop(..., laneShift=2)` over
  `quads = count >> 2`), and a scalar **remainder LOOP** over the last `count & 3` elements
  (`openScalarTailLoop`, 0..3 leftover) instead of the single odd-element guard. Scalars stay
  f64 at the value boundary (a read `f64.promote_f32`, a write/return `f32.demote_f64`), but
  every kernel computes **entirely in f32** — native `f32x4` arithmetic + an `f32` scalar tail
  (`fn.allocF32Local()`), the final reduction promoted to f64 on return. This matches
  llama2.c / a `FloatVector`'s f32-throughout semantics; it diverges from the f64 vec.lisp
  oracle only for non-f32-exact operands (the same class as SIMD reduction associativity), so
  tests use f32-exact (integer / power-of-two) inputs. The `f64x2` path is left
  byte-identical — only an `#f` / single-float operand reaches the f32 branch.
- `zeros`/`ones`/`arange`: scalar fill loops building the block (no SIMD); a literal
  `'single-float` second argument builds an `F32VEC` (f32 stride + a narrowing store,
  `constructorVecType`), else `F64VEC` (the double default, byte-identical to before). The
  `collectCalls` arg-walk skips the quoted element-type designator, like `collectMakeArray`.
- `aref`/`aset`/`length`: delegate to the shared packed helpers (`compileAref`/`compileAset`/
  `compileLength`), width-aware via `elemShift(vecTy)` (f64 `<<3`, f32 `<<2`) and the
  load/store opcode (`f64.load`/`f64.store` vs `f32.load`+promote / demote+`f32.store`).
  `mean`/`norm`: expand to `(/ (sum) (length))` / `(sqrt (dot v v))` and recompile
  (width-agnostic).
- Locals: `Fn.extraLocalTypes` is a `List<Integer>` of raw wasm value-type bytes (not `Ty`)
  so `allocV128Local()` can add `Type.V128` (0x7B) and `allocF32Local()` a bare `Type.F32`
  (0x7D), neither of which has a `Ty` value-model kind; the body is emitted with
  `withLocalsRaw`. Instruction constants (`SIMD_PREFIX` 0xFD, `V128_LOAD`/`STORE`, `F64X2_*`,
  `F32X4_*` — note `f32x4.extract_lane` is 0x1F, NOT 0x1B which is `i32x4.extract_lane`) live
  in `am.ik.wasm.Instruction`; sub-opcodes above 127 (e.g. `f64x2.add` = 0xF0, `f32x4.add` =
  0xE4) use the u32-LEB writer. A simd program flags the memory section via `usesFloatArray`
  (umbrella-based: any `LispFloatArray` literal, `make-array`, or `vec:` call).
- wasmtime enables the SIMD proposal by default, so `--no-gc --simd` v128 runs with a plain
  `wasmtime run` (no `-W gc`). Correctness alone no longer proves v128 ran (the scalar
  default is numerically equivalent), so the unit tests assert `0xFD` presence (v128) /
  absence (scalar) directly.

## Acceleration layer 3 — wasm-GC `--simd` native v128 over `(array (mut v128))` (todo-105)

`--simd` on the DEFAULT `.wasm` backend routes the same twenty-four kernels (the seven
vectorizable ones, the six todo-109 unary ufuncs, and their eleven `-into` siblings) to
emitted v128 runtime helpers.

The apparent blocker — "`v128.load`/`store` address LINEAR memory, so a packed array must
leave the GC heap" — is false, and todo-101 shipped on it before todo-105 corrected it. The
GC proposal's `fieldtype ::= storagetype ::= valtype | packedtype` and `valtype` includes
`vectype = v128`, so **`(array (mut v128))` is a legal GC array and `array.get` on it yields
a v128**. No `v128.load` needed, no arena, no `memory.grow`, no `VEC_HEAP_PTR_ADDR`. `--simd`
still **changes the packed representation**, but to another collected object:

- `TYPE_FARRAY = struct {(ref null eq) dims, (ref null eq) data}` is unchanged; `data` holds
  a `TYPE_VBLOCK = struct {i32 count, i32 kind, (ref null eq) groups}` instead of a
  `TYPE_F64ARR`/`TYPE_F32ARR`. `groups` is a `TYPE_V128ARR = (array (mut v128))`.
- `kind` 0 = f64 (2 lanes), 1 = f32 (4 lanes): the runtime width tag that replaces
  `ref.test $f32arr`, now both widths share one `TYPE_V128ARR`. `count` is the logical
  element count.
- `groups` length is `ceil(count / lanes) + 1`. The `+1` is a **zero sentinel group** so
  `matvec`'s shuffle window at the last group can always `array.get g+1` without a bounds
  trap. 16 bytes per array.
- **No kernel has a scalar tail.** `array.new_default` zero-initializes the v128 elements and
  nothing ever writes past `count`, so the last group's padding lanes are zero:
  `add`/`sub`/`mul`/`scale` map 0 to 0 and `sum`/`dot` fold 0 in. (Edge: `vec:scale v s` with
  an infinite `s` makes the padding NaN — but then the real elements are already ±inf and any
  reduction over them is NaN regardless. Noted, not coded for.)
- **Four new types, `--simd` only**, appended after `TYPE_F32ARR`: `TYPE_V128ARR`,
  `TYPE_VBLOCK`, `TYPE_V_GET` (`(eq,i32)->f64`), `TYPE_V_SET` (`(eq,i32,f64)->f64`).
  Declaring an `(array (mut v128))` at all requires the SIMD proposal, so the type must NOT
  appear in a default module — which is also what keeps the `simd=n` dead-flag guard working.
  The export/import wrapper type bases read `WasmLispCompiler.fixedTypeCount()`
  (`TYPE_F32ARR + 1 + (simd ? SIMD_TYPE_COUNT : 0)`), the same conditional-index trick as
  `userFuncBase()`. A default module's type section is a strict PREFIX of a `--simd` one
  (pinned by `WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecFunctionBlock`),
  so component blobs are untouched.

Mechanics:

- **The kernels are standalone runtime functions**, not inline code: `WasmLispCompiler`
  declares every extra local of a compiled body as one `(ref null eq)` group, so a defun
  body cannot hold a v128/f64/i32/`(ref null $v128arr)` local. `WasmVecSimdRuntimeBuilder`
  hand-writes the local declarations (`withLocals(i32, f64, f32, v128, eq, v128arr)` — that
  fixed order is what all the index arithmetic assumes) for 15 functions. One kernel serves
  both widths by branching on the `kind` field; a mixed-width call traps (`requireSameKind`),
  matching the JVM bridge's hard error. `matvec-into` additionally traps on `ref.eq(out, x)`.
- **The one place the zero padding is not free: a WRITE.** A whole-group store reaches up to
  `lanes - 1` elements past `count`. Harmless when the destination is exactly `count` long (those
  are its own zero padding, and `op(0,0) = 0`), but an `-into` destination LONGER than its operands
  -- which the contract explicitly allows -- has REAL elements there, which the scalar `vec.lisp`
  defun leaves alone. `WasmVecLoops.gcSaveLastGroup` / `gcRestoreLastGroupTail` bracket the group
  loop and blend the last written group, restoring lanes `>= count % lanes` from the destination's
  pre-loop value (read BEFORE the loop, so an `out` aliased with an operand still sees its own
  pre-op lanes). Once per call, not per group. Side benefit: `(vec:scale v s)` with a non-finite `s`
  now leaves zeros in the padding instead of NaN, so that edge case is gone rather than documented.
  Pinned by `wasmGcSimdIntoKernelsDoNotClobberADestinationLongerThanTheOperands`.
- **`_v_new` / `_v_get` / `_v_set`** (the first three emitted functions) own the width branch
  AND the immediate-lane branch: a lane index is an instruction immediate, so reading element
  `i` is `array.get (i >> laneShift)` then a 2-way (f64x2) or 4-way (f32x4) `if`-chain over
  `extract_lane`; a write is the same chain over `replace_lane` plus an `array.set`.
  Centralizing that is what keeps `WasmArrayCompiler` / `WasmQuoteCompiler` /
  `WasmRuntimeBuilder` at one `call` per `aref` / literal / print site. `_v_set` returns the
  value AS STORED (an f32 round-trip at single width), which is exactly `emitPackedWriteF64`'s
  contract. `_v_new` reuses `TYPE_RAT_NEW`'s `(i32,i32)->(ref null eq)` shape, as `_str_build`
  does.
- **`matvec` and the shuffle window**: row *r* of a *d × n* matrix starts at flat element
  `r*n`, i.e. in group `base = (r*n) >> laneShift` at lane `off = (r*n) & (lanes-1)` — both
  loop-invariant per row. `off == 0` reads one `array.get`; otherwise the row group is
  `i8x16.shuffle(groups[base+k], groups[base+k+1])` with the immediate `[c, c+1, .. c+15]`,
  `c = off * elementBytes` (indices ≥ 16 naturally select the second operand, so one formula
  covers both halves). The immediate cannot be computed, so f64 emits 2 row-loop variants and
  f32 emits 4, selected by an `if`-chain on `off` once per row. Two facts make it safe: the
  sentinel group bounds the final `base+k+1` (`floor(x) + ceil(y) <= ceil(x+y)` puts the max
  index exactly at the sentinel), and the last row group's lanes that OVERHANG into the next
  row are multiplied by `x`'s **zero padding**, so they contribute nothing — the same zero
  invariant that removes the tail everywhere else. `--no-gc` still cannot do `matvec` (its
  packed block is rank-1 only). `matvec-into`'s destination may alias NEITHER `x` (each output
  element folds over all of it) NOR `W` (the row windows keep reading it), so the kernel
  `ref.eq`-traps against both -- matching `vec.lisp`, `JvmSimdVectorTemplate` and `VecSimd`, all
  three of which reject both. (todo-101 guarded only `x`; an adversarial review of todo-105 caught
  it. Pinned by `wasmGcSimdMatvecIntoRejectsADestinationAliasingEitherOperand`.)
- **Function indices**: `FUNC_VEC_BASE = FUNC_WRITE_STR_GC + 1`, then `_v_new`/`_v_get`/
  `_v_set` + `_vec_add`..`_vec_reciprocal_into` (`FUNC_COUNT` = 27). Emitted ONLY under `--simd`,
  so `FUNC_USER_BASE` becomes dynamic (`WasmLispCompiler.userFuncBase()`, threaded via
  `Ctx.userFuncBase` into `WasmLambdaCompiler` and `WasmRuntimeBuilder.buildDispatchBody` —
  the only three readers). Every fixed `FUNC_*` below it keeps its value, and a
  non-`--simd` module is **byte-identical** to a build that never knew about the flag.
- **Call-site interception**: `WasmVecSimdCompiler.handles/compile` in `WasmExprCompiler.
  compileCons`, gated on `ctx.simd` — the exact shape of `JvmSimdCompiler`. `mean`/`norm` are
  accelerated transitively (their spliced bodies call `sum`/`dot`); `#'vec:dot` still names
  the scalar defun, as on the JVM. Everything not in the twenty-four keeps running `vec.lisp` over
  the now-grouped surface (`square`/`square-into` are transitive through `mul`).
- **The rest of the packed surface** branches on `ctx.simd` at compile time (one module, one
  repr): `WasmArrayCompiler.compilePackedMakeVblock`/`emitPackedReadF64Vblock`/
  `emitPackedWriteF64Vblock`/`compileElementType`, `WasmQuoteCompiler`'s `#d`/`#f` literals
  (`compilePackedVblockLiteral` — one `v128.const` `array.set` per lane group, skipping
  all-zero groups), and `WasmRuntimeBuilder.emitPrintArray` (count/kind from the vblock,
  elements via `_v_get`; the `--simd` build no longer needs an extra i32 local).
  `length`/`%arrayp`/`array-dimensions` read only `dims` and are untouched.
  `compilePackedMakeVblock` skips the fill loop entirely when `:initial-element` is absent or
  a literal POSITIVE zero — `array.new_default` already wrote that. `-0.0` is deliberately
  excluded (different bits).
- **Shared loop seam**: `WasmVecLoops` holds the four linear v128 bodies (`simdMap2`/
  `simdScale`/`simdSum`/`simdDot`), the four scalar ones, AND the four GC group bodies
  (`gcMap2`/`gcScale`/`gcSum`/`gcDot` over `openGroupLoop`/`closeGroupLoop`).
  `NoGcWasmCompiler` keeps delegating to the linear ones with its locals allocated in the
  original order, so its output stays byte-identical (verified on `--no-gc`,
  `--no-gc --simd`, `--no-gc --simd --optimize`). This is the seam `.todo/100` carved out.
- **Memory**: packed arrays are ordinary GC objects again — measured flat. 700 × 1048576-element
  `vec:add` allocations (5.6 GiB, more than a wasm32 linear memory can even address) complete
  with a peak RSS of ~83 MB; 32000 × 65536-element allocations (16 GiB through the allocator)
  peak at 122 MB, the same as the scalar wasm-GC path's 131 MB. `-into` no longer changes the
  peak; on wasm-GC it is now an allocation-rate optimization, exactly as on the JVM and the
  interpreter. `--no-gc` is the only WASM target left with a never-freed arena.
- **Measured**: `vec:dot` over an 8192-element `#d` vector, 20000 iterations under
  `wasmtime run -W gc` — 10.1 s scalar, 0.10 s `--simd`. The jump is large because `--simd`
  replaces the boxing-heavy scalar defun AND scalarizes to lanes; it is not 2x-per-lane.
  The GC representation costs **1.93x** on the kernel loop against todo-101's linear arena
  (marginal loop time, startup + fill subtracted: 49.5 ms → 95.7 ms). A `.wat` microbenchmark
  isolates the cause: `array.get`'s **bounds check**, which no engine hoists out of the loop.
  Typing the group locals `(ref $v128arr)` instead of `(ref null $v128arr)` removes the null
  check and buys nothing (91.9 ms vs 93.6 ms, i.e. noise), so the nullable local stays. This
  is the deliberate price of letting the collector own the vectors; `--no-gc --simd` is the
  escape hatch and its output is byte-identical to before.
- The scalar element helpers cost more in isolation (`_v_set`'s group read-modify-write is
  ~1.85x an `array.set`). They are invisible behind a BOXED loop: 163.8M `(setf (aref a i) 1.0)` plus
  163.8M `(aref a i)` run in 12.9 s on BOTH paths, because the `dotimes`/`+` around them dominates.
  **They are NOT invisible behind a bare element loop** (corrected 2026-07-09): before todo-107,
  `linalg:add` — a flat `row-major-aref` loop that `--simd` did not intercept — went from 205 ms to
  230 ms on wasm-GC when `--simd` switched the repr to a vblock (9 samples each, non-overlapping),
  i.e. `--simd` was a 12% PESSIMIZATION for a linalg-only program there. **Fixed by `.todo/107`**
  (2026-07-10): the fifteen accelerated `linalg:` members are intercepted too, and `linalg:add` /
  `linalg:dot` now land on 1 ms, exactly where `vec:add` / `vec:dot` are. The cost is still real for
  what stays un-intercepted (`emap`, `inv` on wasm-GC): see `.kb/linalg-simd.md`.
- Composes with `--optimize` (the shaker's `skipSimd` decodes 0xFD; todo-105 added
  `v128.const`/`i8x16.shuffle`'s 16 immediate bytes and `f32x4`/`f64x2.replace_lane`'s lane
  byte) and `--component` (verified end to end under
  `wasmtime run -W gc=y -W component-model-async=y`).
- Tests: `WasmLispCompilerTest` (v128 local declarations present/absent — the local decls are
  the one part of a code section that decodes without a full opcode walker, so an opcode-byte
  scan would false-positive; the default type section is a strict prefix of the `--simd` one
  and the four appended entries are asserted byte for byte; `FUNC_COUNT` delta;
  component/optimize compile). `WasmLispCompilerIntegrationTest` (Docker+wasmtime):
  `wasmGcSimdIsByteIdenticalToTheScalarPathOverTheWholeVecSurface` (both widths, every
  group-padding config, `-into`, GEMV, packed accessors, `make-array`, literals),
  `...Optimized...`, `wasmGcSimdMatvecMatchesTheScalarPathAtEveryRowLaneOffset` (all six
  shuffle variants), `wasmGcSimdPackedAccessorsMatchTheScalarPathAtEveryLane`,
  `wasmGcSimdPackedArraysAreCollectedRatherThanAccumulated` (the 5.6 GiB run — it can only
  pass if the arrays are collected, since a wasm32 linear memory tops out at 4 GiB),
  `wasmGcSimdIntoKernelsReuseTheCallersDestination`, and the runnable dead-flag guard
  `wasmGcSimdModuleNeedsTheSimdProposalAndTheDefaultOneDoesNot`
  (`wasmtime --wasm simd=n --wasm relaxed-simd=n` refuses the `--simd` module — it fails at
  the TYPE section now, not an opcode — and runs the default one; the wasm counterpart of the
  JVM's `NoClassDefFoundError` guard; relaxed-simd must be disabled too or wasmtime rejects
  the flag combination).

## Verification

### The f32-reduction pinning probe (todo-106) — the ONLY test that pins the precision contract

`v = #f(4096.0 1.0 1.0 ... 1.0)`, 1024 elements. `dot(v,v) = 4096^2 + 1023 = 16778239` exactly;
`4096^2` is `2^24`, where the f32 spacing is 2, so the lane holding it swallows every `1.0` added
to it (`2^24 + 1` ties to even) while the other three lanes fold 256 ones each — hence `2^24 + 768
= 16777984` under `--simd`, on all four backends.

| probe | scalar (all backends) | `--simd` (all backends) |
|---|---|---|
| `(round (vec:dot v v))`, `v[0] = 4096.0` | 16778239 | **16777984** |
| `(round (vec:sum v))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (aref (vec:matvec m v) 0))`, 1×1024 | 16778240 | **16777984** |
| any of the above at `#d` width | 16778239 | 16778239 |

The scalar `matvec` prints 16778240, not 16778239: it accumulates in f64 and narrows on store, and
`2^24 + 1023` is an odd multiple of the f32 spacing there, so it ties to even. Pinned three times —
`eval/VecSimdTest`, `codegen/jvm/JvmSimdAccelCompilerTest` (both
`singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`) and
`WasmLispCompilerIntegrationTest.wasmGcSimdSingleFloatReductionsAccumulateInSinglePrecision`.
**Nothing else catches a regression here**: every other `#f` test input stays under `2^24`, where an
f32 accumulator is exact, so they pass on both contracts (`VecSimdTest.dotAndSumMatchTheScalarOracle
ForSingleFloatVectors` uses `arange 200`, whose squared sum is 2646700). `ci-spec.yaml` never passes
`--simd`, so the cross-backend E2E is unaffected; the component leg was verified by hand.

- `-into` (todo-103): `VecSimdTest` (interception guard `#<function vec:add-into>`, each
  kernel vs its allocating sibling at n = 7 and 200, both widths, `(eq o (add-into o ...))`,
  in-place aliasing, `matvec-into` alias error on BOTH paths, mixed width);
  `JvmSimdAccelCompilerTest` (same set + the bridge-embedded dead-flag guard);
  `NoGcWasmCompilerTest` (`allocVec` site count 2 vs 3 under both lowerings — matched on the
  `i32.shl; i32.add; call $__ronto_alloc` sequence, since a bare `0x10 <idx>` scan
  false-positives inside v128 immediates; `0xFD` presence/absence; f32 stride;
  `matvec-into` compile error); `WasmLispCompilerIntegrationTest`
  `noGcRunsDestinationPassingVecKernelsUnderBothLowerings` (wasmtime, both lowerings, both
  widths, a 100000-iteration in-place accumulation); `ci-spec.yaml`
  `vec-destination-passing-kernels` (all four backends).
- Unit: `JvmSimdAccelCompilerTest` (JVM `--simd`: byte-identical to scalar over small
  scalar-tail + large vector-loop arrays, packed-surface interop, bridge-embedded gating; the
  `matvec` GEMV set adds byte-identical scalar-tail + n≥128 vector-loop + concrete-value +
  width-preservation + mixed-width-error + dead-flag cases for both widths);
  `NoGcWasmCompilerTest` (compiled with `compileSimd()` = `NoGcWasmCompiler(false, true)` for
  the v128 cases: `0xFD` opcode presence for both `f64x2` and `f32x4` kernels, `#f`/
  single-float storage narrow/widen opcodes, plain-MVP-module shape, mixed-width + from-list
  compile errors — PLUS the two scalar cases via `compile()` that assert NO `0xFD` and the
  `f64.load/store` (0x2B/0x39) / `f32.load/store` (0x2A/0x38) scalar opcodes over the same
  block). Interpreter/JVM-scalar via the general suites.
- `--no-gc` end-to-end (automated, Docker+wasmtime): `WasmLispCompilerIntegrationTest`
  `noGcRunsDoubleFloatVecKernelsWithF64x2Simd` / `noGcRunsSingleFloatVecKernelsWithF32x4Simd`
  (both now pass `simd=true` to `compileNoGcAndInvoke`) and
  `noGcRunsVecKernelsScalarWithoutSimdMatchingTheV128Results` (the scalar default, `simd=false`)
  compile with `--no-gc` and `wasmtime run --invoke` int-returning `truncate` wrappers (no
  `-W gc`): `vec:dot`/`sum`/`scale`/`add` + `make-array` + `setf aref` correct across every
  tail config (0 / 1 / 3 leftover elements) and matching the interpreter f64 oracle for
  integer-valued inputs, for BOTH widths and BOTH lowerings (the f32 v128 test also runs
  `--optimize`). This is the
  first automated wasmtime run of the `--no-gc` vec kernels (they were structural-only
  before); it surfaced + fixed a pre-existing `WasmTreeShaker` gap — the `--optimize`
  tree-shaker had no case for the `0xFD` SIMD prefix (`skipSimd` added), so `--no-gc
  --optimize` on ANY vec program (f64 or f32) previously threw "unhandled opcode 0xFD".
- Cross-backend: `ci-spec.yaml` `vec-kernels-cross-backend` (interpreter / JVM / WASM P1 /
  component byte-identical; f64-exact inputs so `mean`/`norm` land on exact doubles, plus two
  `vec:matvec` lines — a square + a non-square `#d` matrix → `#d(17.0 39.0)` / `#d(14.0 32.0)`).
  The `ml/nn-vec.lisp` example (single-float XOR net, `vec:matvec` forward pass) runs on
  interpreter/JVM/wasm via `ExamplesE2eTest`. Run the native `CiSpecE2eTest` after editing it.
- Manual `--no-gc`: `wasmtime run --invoke <fn> module.wasm <args>` (result on stderr; filter
  `^warning:`).

## Writing a `--simd` example or benchmark (constraints learned the hard way)

Migrated from `.todo/98` (the "an example that actually shows the `--simd` win" task, closed
2026-07-09 once `simd-dot` / `simd-gemv` / `tiny-llm` shipped). Its full measurement log — the
`convert(F2D)` emulation cliff, the ablations, the two retracted rankings — is
`git show 3df64eb:.todo/98-simd-showcase-example.md`. **Everything about how a particular JVM behaves
is OUR measurement, not vendor-documented behavior: it may live here, never in `doc/**` or an example
header.** The guides say only "whether the Vector API bridge becomes CPU instructions is up to the
JVM that runs the class, so measure".

- **`THRESHOLD = 128` is compared against the ROW LENGTH**, not the total element count
  (`if (cols >= THRESHOLD)` in `simdMatvec`/`matvecF`). A matrix of many short rows runs the scalar
  loop for every row no matter how large it is. Below 128 elements per row/vector the interpreter and
  JVM run a scalar loop; wasm-GC and `--no-gc` have no threshold. `nn-vec.lisp`'s rows are 2 and 4
  long, so `--simd` does literally nothing there. In `tiny-llm.lisp` exactly one of the thirteen
  GEMVs falls below it — `(vec:matvec vt a)`, whose row length is the CONTEXT length because the V
  cache is transposed (12 here) — worth 0.46% of the multiply-adds, and it vectorizes on its own once
  a real context passes 128 tokens.
- **Print only INTEGERS.** WASM prints floats to ~7 significant digits (`2.718281` vs
  `2.7182818284590455`) and its `exp` differs from the JVM's in the low bits, so a float never
  compares across backends. `argmax` is the trick: an integer that depends on every multiply-add yet
  is unmoved by lane-order rounding. This matters MORE since todo-106 — an `#f` reduction under
  `--simd` now genuinely differs from the scalar reference, not just in the last ULP.
- `get-internal-real-time` is in **milliseconds** — an INTEGER on interpreter/JVM, a FLOAT on WASM —
  and `internal-time-units-per-second` does NOT exist. So an elapsed-time line must never be checked
  by `examples.yaml`.
- `--no-gc` cannot compile `linalg:` at all (`&optional` in `linalg::%la-make`) and rejects
  `vec:matvec`. Any example using either is `[interpreter, jvm, wasm]` only. `simd-gemv.lisp` avoids
  `linalg:` entirely — `(make-array (list r c) :element-type 'single-float)` is enough for a packed
  rank-2 matrix — but `vec:matvec` still rules `--no-gc` out.
- Interpreter time budget for `ExamplesE2eTest`: heat3d 0.0 s .. simd-gemv 4.7 s .. deep-digits
  10.1 s .. tiny-llm ~13 s .. mlp 38.4 s.
- `ExamplesE2eTest` can fail spuriously when the GraalVM JIT prints a "Systemic Graal compilation
  failure" warning onto the program's stdout (seen once on `ml/deep-digits.lisp: jvm`). Re-run.
- **Benchmarking discipline.** Run benchmarks SEQUENTIALLY (a parallel subagent corrupts the
  numbers). Take N >= 9 samples and print them ALL before claiming two configurations differ: a
  GraalVM scalar timing turned out bimodal (`226 269 269 271 271 381 383 395 400`) and a median would
  have hidden it. Raise the iteration count until the JIT reaches steady state. Measure allocation
  with `-XX:+UseEpsilonGC -Xmx12g -Xlog:gc` and read heap-used-at-exit (the GC counts are useless).
  And **zsh does not word-split an unquoted `$FLAGS`** — `java $FLAGS -cp . Prog` passes one giant
  argument; write JVM flags inline.
- **Vary the axis you are not thinking about.** Five separate experiments "confirmed" that GraalVM
  cannot vectorize, because every one of them happened to use `#f`. The first `#d` program
  (`simd-dot.lisp`) came out 1100x faster on the interpreter and destroyed the hypothesis; the real
  cause was one un-intrinsified op in the `#f` reduction kernels, now gone (todo-106).

## Names / registration

`LispNames.VEC_PKG` + `VEC_ZEROS`..`VEC_NORM`/`VEC_MATVEC` (+ `VEC_QUALIFIED_AREF`/`_ASET`);
`PackageRegistry.VEC_FUNCTIONS` (external, no `cl` use) + `vecFunctionNames()`. Native
image: `resource-config.json` registers `vec.lisp` (VecLibrary) and
`JvmSimdVectorTemplate.class` (JvmSimdRuntimeBuilder).

## Not done / follow-ups

- `linalg:` is now packed-float and **width-polymorphic** (`.todo/97`): double by default,
  `#f` opt-in via a trailing constructor `element-type`, and every transform preserves the
  input width (so a `#f` from `vec:` is never force-widened to `#d`). See `.kb/linalg.md`.
- `linalg:` acceleration is **DONE** (`.todo/107`, 2026-07-10): fifteen `linalg:` members are
  intercepted on the interpreter, the JVM and wasm-GC, reusing these lane loops. It is a separate
  layer with one structural difference -- a linalg kernel is PARTIAL (it declines general arrays,
  mixed widths, plain numbers and shape errors by returning null, and the call site then runs the
  scalar defun), because unlike `vec:` the linalg defuns accept all of those. Full mechanics,
  precision contract and benchmarks: **`.kb/linalg-simd.md`**.
- Full matrix×matrix **GEMM** (`matmul`): NOT `vec:` (it produces a matrix) — it lives in
  `linalg:`, where todo-107 accelerated it. It needs no transpose after all: rewriting the oracle's
  `ijk` triple loop as **`ikj`** makes `b`'s rows contiguous AND preserves the summation order, so
  the result is bit-identical rather than merely close. See `.kb/linalg-simd.md`. `vec:matvec`
  (GEMV) is still the mat×vec case llama2's single-token decode needs.
- `--no-gc` native `f32x4` GEMV: `matvec` is a `--no-gc` compile error today (no rank-2
  block layout); a native kernel would need explicit dims or a rank-2 `--no-gc` layout.
  wasm-GC `--simd` already ships GEMV (its `$farray` carries dims), and `.todo/99` should
  now reuse `WasmVecLoops.simdDot` + the per-row cursor walk from
  `WasmVecSimdRuntimeBuilder.emitMatvecRows`.
- A stories15M-scale llama2 demo with real weights. `examples/ml/tiny-llm.lisp` (registered
  2026-07-09) is the real-transformer payoff at toy scale — a 2-layer decoder, 13 GEMVs per
  forward pass, deterministic token ids; what is left is a tokenizer and a weight loader.
