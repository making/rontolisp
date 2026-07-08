# vec: package + packed float-array acceleration

The `vec:` package is a set of portable packed-`f64` vector kernels layered on the
dedicated **packed float-array type** (see the packed float-array constraint in
`CLAUDE.md` and `.todo/94`). This file covers the `vec:` package and the two
acceleration layers; the packed representation itself lives in `LispFloatArray`,
`JvmFloatArrayRuntimeBuilder`, `WasmArrayCompiler` (the `$farray` struct) and
`ScalarWasmCompiler` (`F64VEC`).

## The type it rides on

A vector is a rank-1 packed `(array double-float)` — the same unboxed-double array
that `#d(...)` and `(make-array n :element-type 'double-float)` produce, so the generic
`aref` / `(setf aref)` / `length` / `make-array` interoperate on every backend. Element
type is `double-float`: storing a non-real is a type error, and there is no boxed/general
fallback for a packed array (the todo-92 shadow/degrade path is gone). (The single-float
sibling `#f(...)` / `:element-type 'single-float` is a *different* width — todo-95; `vec:`
is currently double-only and does not ride on it.)

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
implementation on the interpreter, the JVM compiler and the wasm-GC compiler (they run the
scalar defuns over the packed repr, unboxed), and the correctness oracle for the two
accelerated backends. `VecLibrary` splices/loads it exactly like `LinalgLibrary`:

- Interpreter: `LispEvaluator` lazy-loads it on the first resolution of a `vec:`-qualified
  function (`VecLibrary.forms()`), mirroring linalg.
- JVM / wasm-GC compile path: `RontoLispCli` (and `RontoPlayground`, the corpus/e2e test
  helpers) call `VecLibrary.process(program)` after user-macro expansion.
- **`--no-gc` is gated OFF** the splice (`RontoLispCli`: `!(outputFile.endsWith(".wasm") &&
  noGc)`): it has no general array type and intercepts the whole `vec:` surface natively.

Members: `zeros`/`ones`/`arange`/`from-list`/`to-list` (construction), `aref`/`aset`/
`length` (thin wrappers), `add`/`sub`/`mul`/`scale` (element-wise, fresh vector), `sum`/
`dot`/`mean`/`norm` (reductions, scalar). `from-list`/`to-list` need cons lists, so they are
portable-backends-only (a `--no-gc` compile error). `(setf (vec:aref v i) x)` →
`(vec:aset v i x)` via `LispMacroExpander.expandSetf` (`VEC_QUALIFIED_AREF`).

## Acceleration layer 1 — JVM `--simd` (jdk.incubator.vector)

`--simd` routes the **six vectorizable kernels** (`add`/`sub`/`mul`/`scale`/`dot`/`sum`) at
their call sites to an embedded `jdk.incubator.vector` bridge, replacing the scalar defun.
`mean`/`norm` are accelerated transitively (their spliced bodies call `sum`/`dot`).

- `JvmSimdVectorTemplate` — the Vector-API kernels (plain Java, compiled by the project;
  the pom adds `--add-modules jdk.incubator.vector` to javac + surefire). Unbox is
  **trivial and zero-copy** in the packed design: cast `(double[]) arg`, use `off = 1 +
  (int)arg[0]`, read via `DoubleVector.fromArray(SPECIES, arr, off + i)`; the result is a
  fresh packed `double[]` (`[1.0, n, ...]`). No shadow logic. `THRESHOLD = 128` gates the
  lane loop vs a scalar loop; the dot two-rounding mul-then-add (not fma) keeps the only
  scalar-vs-vector divergence to reduction associativity.
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

## Acceleration layer 2 — `--no-gc` native v128 (`f64x2.*` / `f32x4.*`)

`ScalarWasmCompiler` lowers the whole `vec:` surface to real fixed-width WASM SIMD over the
`F64VEC`/`F32VEC` block — always on (not gated on `--simd`; it IS the `--no-gc`
implementation of `vec:`, since vec.lisp is not spliced there). `isSimdCall(name)` (a
`"vec:"` prefix test) dispatches in all three passes: `collectCalls` (eligibility:
`requireKnownSimd` + walk-args), `typeOf`/`typeOfSimd` (constructors → `F64VEC`,
element-wise/`scale` → the operand width, `length` → `INT`, else `FLOAT`), and
`compileCall`/`compileSimd`. Each kernel branches on the operand's inferred width
(`packedVecType`): `F64VEC` → the byte-identical `f64x2` path, `F32VEC` → the `f32x4`
sibling.

- **`F64VEC` (double, `f64x2` = 2 lanes/16 bytes):** element-wise (`add`/`sub`/`mul`) two
  f64 lanes per iteration via `v128.load` + `f64x2.<op>` + `v128.store`
  (`openSimdLoop`/`closeSimdLoop` over `pairs = count >> 1`), plus a one-element scalar tail
  when the length is odd (`emitOddTailGuard`). `scale`: `f64x2.splat` the scalar, one
  `f64x2.mul` per pair + tail. `sum`/`dot`: accumulate in a **v128 lane pair**
  (`fn.allocV128Local()`), fold with `f64x2.extract_lane` 0/1 (`emitHorizontalAdd`), plus
  the odd tail. Computes entirely in f64 (its native width).
- **`F32VEC` (single, `f32x4` = 4 lanes/16 bytes; todo-95 Phase 5):** the same shape at
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
- `zeros`/`ones`/`arange`: scalar fill loops building the block (no SIMD), always `F64VEC`
  (no element-type param).
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
- wasmtime enables the SIMD proposal by default, so `--no-gc` simd runs with a plain
  `wasmtime run` (no `-W gc`). There is no scalar fallback on `--no-gc`, so a correct result
  IS the proof the v128 path ran.

## Verification

- Unit: `JvmSimdAccelCompilerTest` (JVM `--simd`: byte-identical to scalar over small
  scalar-tail + large vector-loop arrays, packed-surface interop, bridge-embedded gating);
  `ScalarWasmCompilerTest` (`--no-gc` v128: `0xFD` opcode presence for both `f64x2` and
  `f32x4` kernels, `#f`/single-float storage narrow/widen opcodes, scalar-module shape,
  mixed-width + from-list compile errors). Interpreter/JVM-scalar via the general suites.
- `--no-gc` end-to-end (automated, Docker+wasmtime): `WasmLispCompilerIntegrationTest`
  `noGcRunsDoubleFloatVecKernelsWithF64x2Simd` / `noGcRunsSingleFloatVecKernelsWithF32x4Simd`
  compile with `--no-gc` and `wasmtime run --invoke` int-returning `truncate` wrappers (no
  `-W gc`): `vec:dot`/`sum`/`scale`/`add` + `make-array` + `setf aref` correct across every
  tail config (0 / 1 / 3 leftover elements) and matching the interpreter f64 oracle for
  integer-valued inputs, for BOTH widths (the f32 test also runs `--optimize`). This is the
  first automated wasmtime run of the `--no-gc` vec kernels (they were structural-only
  before); it surfaced + fixed a pre-existing `WasmTreeShaker` gap — the `--optimize`
  tree-shaker had no case for the `0xFD` SIMD prefix (`skipSimd` added), so `--no-gc
  --optimize` on ANY vec program (f64 or f32) previously threw "unhandled opcode 0xFD".
- Cross-backend: `ci-spec.yaml` `vec-kernels-cross-backend` (interpreter / JVM / WASM P1 /
  component byte-identical; f64-exact inputs so `mean`/`norm` land on exact doubles). Run the
  native `CiSpecE2eTest` after editing it.
- Manual `--no-gc`: `wasmtime run --invoke <fn> module.wasm <args>` (result on stderr; filter
  `^warning:`).

## Names / registration

`LispNames.VEC_PKG` + `VEC_ZEROS`..`VEC_NORM` (+ `VEC_QUALIFIED_AREF`/`_ASET`);
`PackageRegistry.VEC_FUNCTIONS` (external, no `cl` use) + `vecFunctionNames()`. Native
image: `resource-config.json` registers `vec.lisp` (VecLibrary) and
`JvmSimdVectorTemplate.class` (JvmSimdRuntimeBuilder).

## Not done / follow-ups

- `linalg:` acceleration (`.todo/93`): linalg.lisp still builds general arrays
  (`:initial-element 0`); migrating it to packed changes its output int→double and needs
  the linalg doc/test reconciliation + a linalg-kernel interceptor. A distinct step.
- User-facing docs (`doc/{en,ja}/**`): `#f` is now `double-float`-typed (non-real store
  errors); a `vec:` reference page + the Arrays/data-types note are still to be written.
- A chained/matmul timing benchmark.
