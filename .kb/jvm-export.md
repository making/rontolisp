# `rontolisp:jvm-export` (typed Java-callable methods) + `--no-main` library mode

`(rontolisp:jvm-export 'name :params '(T...) :returns T :as "javaName")` — the JVM twin of
`rontolisp:wasm-export` ([wasm-export-no-wasi.md](wasm-export-no-wasi.md)): `JvmLispCompiler`
emits a typed `public static` wrapper beside the untyped `(Object...)Object` method. **The
boundary carries the value exactly or it throws.** `compiler.JvmExportDirective` +
`codegen.jvm.JvmExportRuntimeBuilder`; docs `doc/{en,ja}/guides/jvm-library.md`. A no-op
elsewhere, and gated on `!exportDecls.isEmpty()` or `--no-main`, so a flagless program is
byte-identical ([emitted-output-determinism.md](emitted-output-determinism.md)).

## Type mapping (`BoundaryType`, `JvmExportRuntimeBuilder.javaDesc`)

`:s8/:s16/:s32/:s64` -> `byte/short/int/long` (result guarded only); `:u8/:u16` -> `int`,
`:u32/:u64` -> `long`, guarded by `_exArg` in / `_exRes` out, `:u64` >= 2^63 throws; `:float`
-> `double`; `:bool` -> `boolean` (`false`=nil=`null`, `true`=`"T"`); `:string`, `:s-expr` ->
`String`; `:bytes` -> `byte[]` (packed octet vector `byte[]{8, e0, ...}`, copied each way);
`:float-vector`/`:float-matrix` -> `RontoFloatArray`, JVM-ONLY (`jvmOnly()`). Out of range =
`IllegalArgumentException` in / `ArithmeticException` out; wrong representation =
`ClassCastException`, a BARE string included since that is a SYMBOL (`_exStr`;
`.kb/core-representation.md`). `:s-expr` forces the reader runtime on
(`JvmExportRuntimeBuilder.needsReader`). **`:string` framing must not regress**: a Lisp string
stores its frame quotes, so an unframed Java `String` through the untyped method is mis-read
(`GREET("ron")` -> `"hello, o"`). Refused after Pass 1: unknown / non-defun target, arity
mismatch, a variadic target, and a name failing `JvmExportDirective.isJavaMethodName`, leading
with `_`, `main`, or colliding with another export or a MANGLED defun name.

## The packed float array handle

`am.ik.rontolisp.runtime.RontoFloatArray`, ONE class at every rank and width, over a packed
float array ([vec.md](vec.md)); `jvmOnly()` has `WasmExportCompiler.typeDesignator` refuse it
BY NAME instead of failing later in a component lift.

- **Aliasing is the CONTRACT**: `RontoBoundary.floatArrayArgument`/`floatArrayResult` pass
  `handle.packed()` uncopied, only `of(...)`/`to*Array()` copy. Width dispatches in ONE place
  (`widthOf`/`headerAt`/`dimAt`/`dataOffset`) as `Width`; a declared handle forces
  `usesFloatArray` on.
- **`Width` has THREE members** -- it shipped with two, and a caller must not assume the count
  it has now. All three cross the ONE designator pair; the handle reads each width's header its
  own way, which is why the enum is not a `double[]`/`float[]` boolean:

  | `Width` | backing | header | copy-in / copy-out |
  | --- | --- | --- | --- |
  | `DOUBLE_FLOAT` | `double[]` | `1 + rank` | `of(double[], int...)` / `toArray()` |
  | `SINGLE_FLOAT` | `float[]` | `1 + rank` | `of(float[], int...)` / `toFloatArray()` |
  | `BFLOAT16` | `short[]` | `1 + 2 * rank` | `of(short[], int...)` / `toShortArray()` |

  `BFLOAT16` arrived 2026-09-08 (`.todo/689`); before it, a `#bf16` weight matrix reaching the
  boundary was REFUSED by `checkPacked` ("not a packed float array: [S") -- a correct refusal
  and never a wrong number, but a Java caller of a compiled model could not hand a bf16 weight
  matrix across or receive one. `of(short[], ...)`/`toShortArray()` take BIT PATTERNS, not
  values; `get`/`set`/`toArray` still read and write `double` at every width, `toFloatArray`
  widens a bf16 by the SHIFT ALONE (never by way of a `double`, which quiets 126 of the 65536
  patterns). Two slots per dimension because a `short` caps at 32767; the offset is named
  (`BFLOAT16_HEADER_SLOTS_PER_DIM`) and applied in `dataOffset` alone, since `runtime` cannot
  import the emitter's authority `codegen.jvm.JvmPackedFloatWidth`. Same reason the widen /
  narrow pair is a THIRD hand-written copy of `am.ik.rontolisp.BFloat16` (`.kb/bfloat16.md`,
  "The conversion arithmetic census"), swept against it by
  `RontoFloatArrayTest#theNarrowingMatchesTheAuthorityOnEveryF32PatternAndEveryDoubleNaN` and
  `#everyBfloat16PatternWidensExactlyAsTheAuthorityDoes`.
- They travel VERBATIM at canonical names, NOT renamed per program like the acceleration
  bridges ([template-class-embedding.md](template-class-embedding.md)) — one canonical boundary
  TYPE is what keeps two rontolisp libraries' vectors interoperable.
- `--gpu`: the handle does NOT materialize; it adopts the generated class (`ldc thisClass`) and
  resolves its private `_gpuMaterialize`/`_gpuWritten` through `MethodHandles` ([gpu.md](gpu.md)).
  A lazy result's host array is the HEADER ALONE, so `checkPacked` requires only the header
  (`1 + rank`, or `1 + 2 * rank` at `BFLOAT16`) and not the elements.

## What travels

`am.ik.rontolisp.runtime` is THE package that ships inside someone else's artifact; hand-kept
lists (`JvmRuntimeClassFilesTest` names them all):

| list | travels when | what it is |
| --- | --- | --- |
| `JvmExportRuntimeBuilder.RUNTIME_CLASS_FILES` | a `:float-vector`/`:float-matrix` export | `RontoFloatArray` + `RontoBoundary` |
| `JvmHttpHandlerRuntimeBuilder.RUNTIME_CLASS_FILES` | `rontolisp:http-handler` / `%http-server-start` | `RontoHttpServer`, `RontoHttpClack`, `RontoClackEnv`, `RontoHashTable` |
| `JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES` | a `.war` or `<servlet>true</servlet>`, IN ADDITION to the served list | `RontoHttpServlet` + `RontoHttpServletInitializer` |
| `JvmFetchRuntimeBuilder.RUNTIME_CLASS_FILES` | `rontolisp:fetch` | `RontoFetch` -- the whole transport (`.kb/fetch-http.md`) |
| `JvmHashRuntimeBuilder.RUNTIME_CLASS_FILES` | any hash-table use (`equalpKey` for an `equalp` table, the tombstone machinery for every table -- `.kb/hash-tables.md`) | `RontoHashTable` again |
| `JvmIoRuntimeBuilder.RUNTIME_CLASS_FILES` | an `open` that can ask for `:direction :io` / `:if-exists :overwrite` (`LispMacroExpander.opensBidirectionally`, `.kb/read-load-streams.md`) | `RontoIoFileStream` -- the interpreter runs the same class |

Path: `JvmRuntimeClassFiles.read` -> `JvmLispCompiler.runtimeClassFiles()` -> `RontoLispCli`
(beside `-o X.class`, INSIDE `-o X.jar`) and `LispSourceSet` (plugin, `target/classes`);
`resource-config.json` covers the native binary. The same map carries a program's own
`Name$PartN.class` files when its pool outgrew one class (`.kb/jvm-method-size-limits.md`),
keyed in the class's own package rather than at a canonical name (a default-package part has
no directory, which the CLI's writer allows for). A split keeps the exports and the defuns
behind them in the class itself, so the Java API does not move. **The price**: a `runtime` class imports
nothing at all, not even `@Nullable` — so `RontoHashTable.get` takes the absent value and
`RontoHttpServer` nests its own `ServerException`. ONE exception, the war row's
`jakarta.servlet`, `provided` (`.kb/http-server.md`).

## Two consequences of declaring an export

- **Tree-shaker root, the third liveness source** beside `main` and the dispatchable-funcId
  set ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)): the wrapper's
  name joins `JvmClassShaker`'s roots with `main` and `_apply`/`handle`/`run`/`call`, and
  without one a library shakes to nothing under `--optimize` (ON by default). `--no-prune` is
  the AST splice pruner, a different mechanism.
- **The top level moves into `<clinit>`** (the `--no-wasi` reactor precedent): `_top$0..N`, the
  `System.out.flush()` epilogue and the `JvmUncaughtHandler` table become `_top$run`, invoked
  LAST after the ThreadLocal/stream/layout/struct seeding. A signalling top-level form therefore
  surfaces as `ExceptionInInitializerError`, poisoning the class; `uiop:quit` kills the JVM.
- **Such a class keeps the plain `main`** -- no sized-stack launcher ([interpreter-stack.md](
  interpreter-stack.md), `JvmSizedMainBuilder`): `<clinit>` runs on the caller's thread before
  `main` exists to move it, so a launcher would add bytes and move nothing. The launcher is
  emitted only for a class with a `main` whose top level runs IN `main`; a `--no-main`, jvm-export
  or war output is byte-identical to before it (checked 2026-09-19).

## `--no-main` and `-o out.jar`

`--no-main` drops `main` (`JvmLispCompiler.noMain(boolean)`, `CliOptions.noValueKeys` +
`RontoLispCli`), refusing a non-JVM output, a jar with no `--class-name`, and — in the compiler,
so embedders get it too — a program with no jvm-export; it also decides a jar's `Main-Class`.
`.class` and `.jar` are the SAME compile (`cli/JvmJarWriter`, `cli/MavenCoordinates`,
`cli/JvmArtifactOptions`), the two `RontoLispCli.jvmOutput` extensions.

- **A `.class` path's directory is the PACKAGE only when it can be one**
  (`JvmArtifactOptions.classNameFromClassPath`, by JVMS 4.2.2): `-o out-dir/T3.class` emits
  `out-dir.T3`, while an ABSOLUTE path (EMPTY segment -> `ClassFormatError`) or `./`/`../`
  leaves the stem as the whole name and `classRoot` roots the travelling classes at that
  directory; an impossible stem is REFUSED naming `--class-name`.
- `--class-name` is required by the LIBRARY jar, not by jar output: a program jar derives one
  from its stem (`classNameFromStem`, CamelCase: `my-app-1.0.0.jar` -> `MyApp100`), a stray `.`
  there being an unresolvable `Main-Class`.
- **Entries, in this fixed order**: manifest (`Main-Class` exactly when the class HAS a main),
  the `META-INF/maven` pair under `--maven-coordinates`, the class at its package path, then
  `runtimeClassFiles()` SORTED (`Map.copyOf` iterates randomly), under one fixed DOS timestamp
  (`setTimeLocal`). **Omitting them is a `NoClassDefFoundError` in the CONSUMER.**
- The embedded `META-INF/maven/<g>/<a>/pom.xml` + `pom.properties` let `install:install-file`
  run with no coordinate flags; `<dependencies/>` is EMPTY, not omitted; `--emit-pom` writes
  the pom beside the jar, guarded against `MavenCoordinates.POM_MARKER`.

## `rontolisp-maven-plugin` — `src/main/lisp` as a source set

`src/main/lisp/com/acme/Kernels.lisp` beside `src/main/java`, one jar out. Own module outside
the root reactor, on the rontolisp artifact by coordinates; goals `compile`/`testCompile`, a
parameter per JVM-reaching CLI flag under the same name.

- **The path IS the class name**, refused by name for a non-identifier segment, and only for a
  file that earns a class (`string-utils.lisp` beside `Kernels.lisp` is fine).
- **`process-sources`, not `compile`**: Maven merges lifecycle-injected plugins AHEAD of
  POM-declared ones, so a `compile`-bound goal runs after `maven-compiler-plugin:compile` and
  `src/main/java` fails with `package ... does not exist`.
- **A source set is Lisp, not a pile of exports**: `noMain` defaults to TRUE here (the CLI's
  flag does not) and a file becomes a class exactly when it has exports;
  `JvmSourceCompiler.compileIfExported` asks the EXPANDED program.
- **`<servlet>true</servlet>` is `-o app.war`'s mode**: `JvmSourceCompiler.servlet` adds
  `WAR_RUNTIME_CLASS_FILES` plus the `META-INF/services/jakarta.servlet.ServletContainerInitializer`
  line naming `RontoHttpServletInitializer` (no `web.xml`), and forces `noMain` off — so every
  file needs its own handler against `servletMode && !usesHttpHandler` (`.kb/http-server.md`)
  and **shared code must be `(load ...)`ed, not a sibling `.lisp`**. `CompileMojo` checks
  `${project.packaging}` — not `TestCompileMojo`, whose classes never reach a war.
- **Staleness is all-or-nothing** (a `(load "...")`ed file's own timestamp need not move), so
  state is a STATUS FILE (`target/rontolisp/compile-status.txt`), not the output directory.
- It compiles IN PROCESS: `cli/CompileFrontend` (the shared, order-critical front end) +
  `cli/JvmSourceCompiler`, whose `compileProgram` also serves `-o out.class`/`-o out.jar`;
  `cli/CompileDiagnostics` reaches Maven as a `MojoFailureException`.

## Tests

`JvmExportTest` (framing, handle identity, `<clinit>` signalling, shaker roots),
`JvmExportExampleTest` (`examples/jvm/kernels-library.lisp` from Java), `JvmArtifactOptionsTest`,
`RontoLispCliTest`, `JvmSourceCompilerTest#theEmbeddedCompileIsByteIdenticalToTheCommandLines`,
`JvmRuntimeClassFilesTest`, `JvmHttpHandlerTravellingRuntimeTest`, `RontoFloatArrayTest`,
`MavenBuildE2eTest`, `e2e/JarMavenConsumerE2eTest` (`-Drontolisp.jar.e2e=true`), and the no-op
pins in `LispEvaluatorTest` / `WasmLispCompilerIntegrationTest`.
