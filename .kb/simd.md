# simd: package + packed float-array acceleration

The `simd:` package is a set of portable packed-`f64` vector kernels layered on the
dedicated **packed float-array type** (see the packed float-array constraint in
`CLAUDE.md` and `.todo/94`). This file covers the `simd:` package and the two
acceleration layers; the packed representation itself lives in `LispFloatArray`,
`JvmFloatArrayRuntimeBuilder`, `WasmArrayCompiler` (the `$farray` struct) and
`ScalarWasmCompiler` (`F64VEC`).

## The type it rides on

A simd vector is a rank-1 packed `(array double-float)` — the same unboxed-double array
that `#f(...)` and `(make-array n :element-type 'double-float)` produce, so the generic
`aref` / `(setf aref)` / `length` / `make-array` interoperate on every backend. Element
type is `double-float`: storing a non-real is a type error, and there is no boxed/general
fallback for a packed array (the todo-92 shadow/degrade path is gone).

Per-backend repr: interpreter `record LispFloatArray(double[] data, int[] dims)`; JVM a
bare `double[]` with an embedded `[rank, dim..., data...]` header (data offset `1 + rank`,
so a rank-1 vector is `[1.0, n, e0..]`); wasm-GC a distinct `TYPE_FARRAY` struct over an
`(array (mut f64))`; `--no-gc` a `[count:i32][count f64]` linear-memory block (`Ty.F64VEC`).

## simd.lisp = the scalar reference / cross-backend oracle

`src/main/resources/am/ik/rontolisp/eval/simd.lisp` defines every `simd:` function as a
plain `defun` over `make-array :element-type 'double-float` / `aref` / `length`. It is the
implementation on the interpreter, the JVM compiler and the wasm-GC compiler (they run the
scalar defuns over the packed repr, unboxed), and the correctness oracle for the two
accelerated backends. `SimdLibrary` splices/loads it exactly like `LinalgLibrary`:

- Interpreter: `LispEvaluator` lazy-loads it on the first resolution of a `simd:`-qualified
  function (`SimdLibrary.forms()`), mirroring linalg.
- JVM / wasm-GC compile path: `RontoLispCli` (and `RontoPlayground`, the corpus/e2e test
  helpers) call `SimdLibrary.process(program)` after user-macro expansion.
- **`--no-gc` is gated OFF** the splice (`RontoLispCli`: `!(outputFile.endsWith(".wasm") &&
  noGc)`): it has no general array type and intercepts the whole `simd:` surface natively.

Members: `zeros`/`ones`/`arange`/`from-list`/`to-list` (construction), `aref`/`aset`/
`length` (thin wrappers), `add`/`sub`/`mul`/`scale` (element-wise, fresh vector), `sum`/
`dot`/`mean`/`norm` (reductions, scalar). `from-list`/`to-list` need cons lists, so they are
portable-backends-only (a `--no-gc` compile error). `(setf (simd:aref v i) x)` →
`(simd:aset v i x)` via `LispMacroExpander.expandSetf` (`SIMD_QUALIFIED_AREF`).

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
  using the simd package at all embeds the bridge (the dead defuns are shaken by `--optimize`).

## Acceleration layer 2 — `--no-gc` native v128 (`f64x2.*`)

`ScalarWasmCompiler` lowers the whole `simd:` surface to real fixed-width WASM SIMD over the
`F64VEC` block — always on (not gated on `--simd`; it IS the `--no-gc` implementation of
`simd:`, since simd.lisp is not spliced there). `isSimdCall(name)` (a `"simd:"` prefix test)
dispatches in all three passes: `collectCalls` (eligibility: `requireKnownSimd` +
walk-args), `typeOf`/`typeOfSimd` (constructors/element-wise → `F64VEC`, `length` → `INT`,
else `FLOAT`), and `compileCall`/`compileSimd`.

- Element-wise (`add`/`sub`/`mul`): two f64 lanes per iteration via `v128.load` +
  `f64x2.<op>` + `v128.store` (`openSimdLoop`/`closeSimdLoop` over `pairs = count >> 1`),
  plus a one-element scalar tail when the length is odd (`emitOddTailGuard`).
- `scale`: `f64x2.splat` the scalar, one `f64x2.mul` per pair + scalar tail.
- `sum`/`dot`: accumulate in a **v128 lane pair** (`fn.allocV128Local()`), fold with
  `f64x2.extract_lane` 0/1 (`emitHorizontalAdd`), plus the odd tail.
- `zeros`/`ones`/`arange`: scalar fill loops building the block (no SIMD).
- `aref`/`aset`/`length`: delegate to the shared packed helpers (`compileAref`/`compileAset`/
  `compileLength`). `mean`/`norm`: expand to `(/ (sum) (length))` / `(sqrt (dot v v))` and
  recompile.
- Locals: `Fn.extraLocalTypes` is a `List<Integer>` of raw wasm value-type bytes (not `Ty`)
  so `allocV128Local()` can add `Type.V128` (0x7B), which has no `Ty` value-model kind; the
  body is emitted with `withLocalsRaw`. Instruction constants (`SIMD_PREFIX` 0xFD,
  `V128_LOAD`/`STORE`, `F64X2_*`) live in `am.ik.wasm.Instruction`; sub-opcodes above 127
  (e.g. `f64x2.add` = 0xF0) use the u32-LEB writer. A simd program flags the memory section
  via `usesFloatArray` (which now also returns true for any `simd:` call).
- wasmtime enables the SIMD proposal by default, so `--no-gc` simd runs with a plain
  `wasmtime run` (no `-W gc`). There is no scalar fallback on `--no-gc`, so a correct result
  IS the proof the v128 path ran.

## Verification

- Unit: `JvmSimdAccelCompilerTest` (JVM `--simd`: byte-identical to scalar over small
  scalar-tail + large vector-loop arrays, packed-surface interop, bridge-embedded gating);
  `ScalarWasmCompilerTest` (`--no-gc` v128: `0xFD` opcode presence, scalar-module shape,
  from-list compile error). Interpreter/JVM-scalar via the general suites.
- Cross-backend: `ci-spec.yaml` `simd-kernels-cross-backend` (interpreter / JVM / WASM P1 /
  component byte-identical; f64-exact inputs so `mean`/`norm` land on exact doubles). Run the
  native `CiSpecE2eTest` after editing it.
- Manual `--no-gc`: `wasmtime run --invoke <fn> module.wasm <args>` (result on stderr; filter
  `^warning:`).

## Names / registration

`LispNames.SIMD_PKG` + `SIMD_ZEROS`..`SIMD_NORM` (+ `SIMD_QUALIFIED_AREF`/`_ASET`);
`PackageRegistry.SIMD_FUNCTIONS` (external, no `cl` use) + `simdFunctionNames()`. Native
image: `resource-config.json` registers `simd.lisp` (SimdLibrary) and
`JvmSimdVectorTemplate.class` (JvmSimdRuntimeBuilder).

## Not done / follow-ups

- `linalg:` acceleration (`.todo/93`): linalg.lisp still builds general arrays
  (`:initial-element 0`); migrating it to packed changes its output int→double and needs
  the linalg doc/test reconciliation + a linalg-kernel interceptor. A distinct step.
- User-facing docs (`doc/{en,ja}/**`): `#f` is now `double-float`-typed (non-real store
  errors); a `simd:` reference page + the Arrays/data-types note are still to be written.
- A chained/matmul timing benchmark.
