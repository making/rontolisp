# 96 — rename the `simd:` package to `vec:`

Goal: rename the user-facing `simd:` Lisp package to `vec:`. The package is a portable
packed **vector** abstraction that maps onto hardware SIMD *where the backend supports it*
(JVM `--simd`, `--no-gc`); on the interpreter, JVM-default and WASM-GC it is a correct
**scalar** fallback. Naming the package after the (optional) implementation strategy
over-claims -- Java's `jdk.incubator.vector` ("Vector API", scalar-fallback + JIT→SIMD) and
numpy set the precedent: name the package for the abstraction, keep "SIMD" for the
acceleration mechanism.

## Decision (2026-07-08, user sign-off)

- **Package `simd:` → `vec:`** (short; avoids the `cl:vector` symbol double-take that
  `vector:` would cause).
- **`--simd` flag STAYS `--simd`.** It names the acceleration mechanism (jdk.incubator.vector
  / WASM `v128`), which genuinely IS SIMD. Clean split: **`vec:` = the abstraction,
  `--simd` = how it's accelerated.**
- **SIMD-acceleration internals KEEP their `Simd` names** -- they implement SIMD and are
  correctly named: `JvmSimdCompiler`, `JvmSimdVectorTemplate`, `JvmSimdRuntimeBuilder`,
  `programUsesAnyAcceleratedSimdOp`, `ScalarWasmCompiler`'s `compileSimd*`/`isSimdCall`/
  `F64X2_*` lowering, the embedded `RontoLispSimdBridge`. Only the **package identity** and
  the **library** rename.
- **Hard rename, no `simd:` alias.** Pre-1.0, no external users; a transition nickname is
  not worth the permanent ambiguity. (If ever wanted, `simd` could be added as a package
  *nickname* for `vec` via `PackageRegistry` -- but default to NOT.)

## Why now (before `.todo/95` Phase 4)

Cheapest moment: Phases 4/5/6 and Part 2 (`vec:matvec`) will ADD more `simd:` references
(WASM-GC single-float, `--no-gc` `f32x4`, ci-spec cases, docs, the llama2 example). Renaming
before they land minimizes total churn. **Sequence: do this rename, THEN `.todo/95` Phase 4.**

## Scope / checklist

The rename is the **package prefix `simd`→`vec` + the library file/loader**, NOT the member
names (`add`/`sub`/`mul`/`scale`/`dot`/`sum`/`mean`/`norm`/`aref`/`aset`/`length`/`zeros`/
`ones`/`arange`/`from-list`/`to-list` are unchanged; only the package they live in changes).

- **`LispNames`**: `SIMD_PKG = "simd"` → `VEC_PKG = "vec"`; `SIMD_QUALIFIED_*` values
  (`simd:aref`/`simd:aset` → `vec:aref`/`vec:aset`). Rename the package-identity Java
  constants to `VEC_*` for clarity; the member-name constants (`SIMD_ADD = "add"` ...) may
  keep their Java identifiers or move to `VEC_*` -- executor's call, but be consistent.
- **`PackageRegistry`**: register `vec` (was `simd`); `SIMD_FUNCTIONS`/`simdFunctionNames()`
  → `VEC_FUNCTIONS`/`vecFunctionNames()`; `CL_SYMBOLS`/package classification; canonical
  name + any nickname table.
- **The library**: `src/main/resources/am/ik/rontolisp/eval/simd.lisp` → `vec.lisp`, with all
  `simd:`/`simd::` prefixes → `vec:`/`vec::` (incl. `simd::%make-like`/`simd::%map2` →
  `vec::%...`). `SimdLibrary.java` → `VecLibrary.java` (loads `vec.lisp`; the
  `forms(Features)`/`process(program, Features)` shape from `.todo/95` Phase 3 stays). Update
  all callers: `RontoLispCli`, `LispEvaluator` (the lazy-load `isSimdQualified`→
  `isVecQualified`), `RontoPlayground` (both), the corpus tests, `JvmSimdAccelCompilerTest`.
- **JVM accel wiring**: `JvmSimdCompiler.handles`/`compile` match on the member names via
  the (renamed) `LispNames` constants -- update the references, keep the class name `Simd*`.
  Same for `ScalarWasmCompiler` `SIMD_MEMBERS`/`isSimdCall` (now tests a `vec:` prefix).
- **`--simd` gate**: `programUsesAnyAcceleratedSimdOp` walks for the (renamed) `vec:` member
  call sites; keep the flag + method names `Simd`.
- **ci-spec** (`src/test/resources/ci-spec.yaml`): any `simd:` case → `vec:`.
- **Docs** (`doc/en/**` + `doc/ja/**`, `_catalog.yaml`, nav, the reference table row) +
  **`.kb/simd.md`** (→ `.kb/vec.md`? keep filename but retitle, or rename -- update
  `.kb/README.md` index either way) + **examples** (`examples/**` any `simd:` usage).
- **Tests**: every `simd:` in a Lisp string across `JvmSimdAccelCompilerTest`,
  `JvmFloatArrayTest`?, `LispEvaluatorTest`, `WasmLispCompilerIntegrationTest`,
  `ScalarWasmCompilerTest`, `LispFloatArrayTest`, e2e -- grep `"simd:"` broadly.

## Verify

- `grep -rn "simd:" src/ doc/ examples/ .kb/` returns only the intended `Simd`-named accel
  internals (Java identifiers), no stray `simd:` package refs.
- `./mvnw spring-javaformat:apply test` GREEN; web-profile compile; javadoc clean (Version
  only).
- 4-backend CLI smoke: `(vec:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))` → `32.0` on interpreter /
  JVM / JVM `--simd` / WASM-GC; `#f` width-preserving on interpreter+JVM; `--no-gc` `vec:`
  still lowers to `v128`.
- Native `CiSpecE2eTest` if any `ci-spec` `vec:`/`simd:` case exists (cross-backend output
  unchanged -- pure rename).

## Note

`.todo/95` Part 2 already uses the target name **`vec:matvec`** (GEMV) -- build it under the
new name, don't create a `simd:matmul`. General matrix×matrix `matmul` stays in `linalg:`.
