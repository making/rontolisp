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
`ref.test $f32arr` (todo-95 Phase 4); `--no-gc` a `[count:i32][count f64]` (`Ty.F64VEC`,
8-byte stride) or `[count:i32][count f32]` (`Ty.F32VEC`, 4-byte stride) linear-memory
block, keyed off the `#d`/`#f` literal or the `:element-type` (todo-95 Phase 5).

## vec.lisp = the scalar reference / cross-backend oracle

`src/main/resources/am/ik/rontolisp/eval/vec.lisp` defines every `vec:` function as a
plain `defun` over `make-array :element-type 'double-float` / `aref` / `length`. It is the
implementation on the interpreter (unless `--simd` — see acceleration layer 0), the JVM
compiler and the wasm-GC compiler (they run the scalar defuns over the packed repr,
unboxed), and the correctness oracle for the accelerated paths. `VecLibrary` splices/loads
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
`length` (thin wrappers), `add`/`sub`/`mul`/`scale` (element-wise, fresh vector), `sum`/
`dot`/`mean`/`norm` (reductions, scalar), `matvec` (GEMV — a rank-2 matrix × a rank-1
vector → a fresh rank-1 vector, todo-95 Part 2; the scalar defun reads `(aref w i j)` over
`(array-dimensions w)` and allocates via `vec::%make-like`). `from-list`/`to-list` need cons
lists, so they are portable-backends-only (a `--no-gc` compile error); `matvec` needs a
rank-2 matrix, so it is a `--no-gc` compile error too. `(setf (vec:aref v i) x)` →
`(vec:aset v i x)` via `LispMacroExpander.expandSetf` (`VEC_QUALIFIED_AREF`).

## Acceleration layer 0 — interpreter `--simd` (jdk.incubator.vector), opt-in

`rontolisp prog.lisp --simd` (interpret, no `-o`) runs the same seven vectorizable kernels
on the Vector API instead of the scalar defuns. The DEFAULT interpreter is unchanged — it
is the cross-backend byte-identity oracle, and `ci-spec.yaml` never passes `--simd`.

- `eval.VecSimdKernels` — the lane loops, `static`, over the interpreter's **bare**
  `double[]`/`float[]` + explicit `rows`/`cols` (a `LispDoubleFloatArray`/`LispSingleFloatArray`
  has no in-array dimension header, unlike the compiled repr). Mirrors
  `JvmSimdVectorTemplate` operation for operation (same `SPECIES_PREFERRED`, `THRESHOLD =
  128`, two-rounding mul-then-add, `F2D` per-lane widening for the f32 reductions,
  f64-then-narrow `scaleF`), so interpreter `--simd` ≡ compiled `.class --simd` bit-for-bit.
  It is NOT reused from `codegen.jvm` — `eval` may not depend on it (package rule), and the
  template's kernels are written against the header-in-array layout anyway.
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
  never enters the browser image. Keeping VecSimd's kernel references confined to those two
  methods is what makes the substitution sufficient.
- Tests: `eval/VecSimdTest` (every kernel vs the scalar oracle at both widths, below and
  above `THRESHOLD`; the `#<function vec:dot>` vs `#<lambda>` interception guard; mixed-width
  and rank errors). Measured on the native binary: `vec:dot` over an 8192-element `#f`
  vector, 2000 iterations — 9.4s scalar → 1.7s `--simd` (5.6x, identical result).

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
  → a fresh `float[]`, width preserved; `sum`/`dot` → an f64 scalar accumulated via per-lane
  `F2D` widening). Mixed single/double operands are a hard `IllegalArgumentException`.
- **`simdMatvec` (GEMV, todo-95 Part 2):** the `simdDot` lane loop run once per row of a
  rank-2 matrix `W` — header `[2, d, n, ...]` so `ow = 1 + (int)W[0] = 3`, `d = (int)W[1]`
  rows, `n = (int)W[2]` cols; `r[2 + row] = dot(W row, x)` into a fresh length-`d` vector.
  ONE bridge call covers the whole matrix (amortizing entry over `d` rows). Width-polymorphic
  like the rest (`matvecF` mirrors `dotF`, narrowing each row's f64 acc to f32 on store).
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
`Sum`/`Dot`) early-return to a `compileScalar*` seam when `this.simd` is false; the v128
code is left untouched (so `--no-gc --simd` output is byte-identical to the pre-todo-100
`--no-gc` output). `isSimdCall(name)` (a `"vec:"` prefix test) dispatches in all three
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
  switch. **Seam for `.todo/101`:** the `emitScalar{Map2,Scale,Sum,Dot}Loop` helpers take raw
  linear-memory locals (data pointer past the `[count]` header + element count + width), so
  the wasm-GC backend can later drive the same loop over a linear-memory mirror of a packed
  GC array. Here "scalar" means non-SIMD, distinct from the non-GC value model the compiler
  is named for.
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

## Verification

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

## Names / registration

`LispNames.VEC_PKG` + `VEC_ZEROS`..`VEC_NORM`/`VEC_MATVEC` (+ `VEC_QUALIFIED_AREF`/`_ASET`);
`PackageRegistry.VEC_FUNCTIONS` (external, no `cl` use) + `vecFunctionNames()`. Native
image: `resource-config.json` registers `vec.lisp` (VecLibrary) and
`JvmSimdVectorTemplate.class` (JvmSimdRuntimeBuilder).

## Not done / follow-ups

- `linalg:` is now packed-float and **width-polymorphic** (`.todo/97`): double by default,
  `#f` opt-in via a trailing constructor `element-type`, and every transform preserves the
  input width (so a `#f` from `vec:` is never force-widened to `#d`). See `.kb/linalg.md`.
- `linalg:` acceleration (`.todo/93`): a linalg-kernel `--simd`/`v128` interceptor (like the
  `vec:` bridge) is still a distinct step -- linalg runs the scalar packed defuns everywhere.
- Full matrix×matrix **GEMM** (`matmul`): NOT `vec:` (it produces a matrix) — it belongs in
  `linalg:` and needs a transpose that GEMV avoids. `vec:matvec` (GEMV) is the mat×vec case
  llama2's single-token decode needs; GEMM is deferred (prefill batching only).
- `--no-gc` native `f32x4` GEMV: `matvec` is a `--no-gc` compile error today (no rank-2
  block layout); a native kernel would need explicit dims or a rank-2 `--no-gc` layout.
- A chained/GEMV timing benchmark + a stories15M-scale llama2 demo (the `ml/nn-vec.lisp`
  XOR net is the small-scale `vec:matvec` example; a real transformer is the payoff).
