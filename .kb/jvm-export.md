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
`String`; `:bytes` -> `byte[]` (the octet vector's own `byte[]`, copied each way as the WASM
boundary copies the same designator: `Arrays.copyOf` in `_exBytesIn` / `_exBytesOut`);
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
| `JvmHttpHandlerRuntimeBuilder.RUNTIME_CLASS_FILES` | `rontolisp:http-handler` / `%http-server-start` | `RontoHttpServer`, `RontoHttpClack`, `RontoClackEnv`, `RontoHashTable` and its nested classes |
| `JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES` | a `.war` or `<servlet>true</servlet>`, IN ADDITION to the served list | `RontoHttpServlet` + `RontoHttpServletInitializer` |
| `JvmFetchRuntimeBuilder.RUNTIME_CLASS_FILES` | `rontolisp:fetch` | `RontoFetch` -- the whole transport -- and `RontoFetch$BodyPump`, the subscriber filling a reply's body stream as it arrives (`.kb/fetch-http.md`), and the `RontoFuture` it answers |
| `JvmAsyncRuntimeBuilder.RUNTIME_CLASS_FILES` | the async runtime (`usesAsyncRuntime`: an await, an async body, a timer, a stream verb, a served handler, ...) | `RontoFuture` -- the future that knows whether it is known settled (`.kb/async-await.md`, "Where the eager part ends") |
| `JvmLispCompiler.INFLATE_RUNTIME_CLASS_FILES` | `rontolisp::%inflate-new` (the Clojure HTTP client) | `RontoInflate` and its `$Code`/`$Stop` -- the decoder of a compressed reply (`.kb/fetch-http.md`, "Decompression") |
| `JvmLispCompiler.CONDITION_VARIABLE_RUNTIME_CLASS_FILES` | `rontolisp::%condition-variable-new` (the `bordeaux-threads` shim) | `RontoConditionVariable` -- the interpreter runs the same class (`.kb/mutexes.md`) |
| `JvmLispCompiler.CONS_CAS_RUNTIME_CLASS_FILES` | `rontolisp::%cons-cas-car` / `-cdr` (the atomics arm) | `RontoConsCas` (`.kb/asdf.md`, `eval/AtomicsForms`) |
| `JvmLispCompiler.STUB_CLASSES_RUNTIME_CLASS_FILES` | `rontolisp::%host-stub-class` (a Clojure `java:` program's class values) | `RontoStubClasses` and its `$Loader`/`$Pool` -- the interpreter defines through the same class (`.kb/clojure-frontend.md`, "Class values") |
| `JvmSignalThrow.RUNTIME_CLASS_FILES` | a compiled signal whose text is its condition's report (`.kb/error-handling.md`, "A signal renders nothing") | `RontoSignal` -- the throwable rendering it when `getMessage` asks |
| `JvmInterruptPoll.RUNTIME_CLASS_FILES` | a program naming `rontolisp:interactive-interrupt` that its own `main` runs (`JvmLispCompiler.takesInterrupts`) | `RontoInterruptSignal` -- SIGINT as that condition (`.kb/error-handling.md`, "SIGINT") |
| `JvmComplexRuntimeBuilder.RUNTIME_CLASS_FILES` | a program that may observe a complex (`ComplexCapability`) | `RontoComplex` -- the complex holder (`.kb/jvm-complex.md`) |
| `JvmQuantizedMatrixRuntimeBuilder.RUNTIME_CLASS_FILES` | `rontolisp:quantize` / `make-quantized-matrix` (`usesQuantized`) | `RontoQuantizedMatrix` -- the quantized matrix's holder (`.kb/quantized-matrix.md`) |
| `JvmHashRuntimeBuilder.RUNTIME_CLASS_FILES` | any hash-table use: every table is one (`.kb/hash-tables.md`, "Representation") | `RontoHashTable` again, with `$Entry`, `$Flat`, `$Trie`, `$Node`, `$VNode` -- a nested class is its own file, so each is listed |
| `JvmSocketRuntimeBuilder.RUNTIME_CLASS_FILES` | a tcp or tls built-in (`usesSockets`) | `RontoSocketInput` -- a connected socket's input buffer; the interpreter reads through the same class (`.kb/tcp-sockets.md`, "A byte at a time") |
| `JvmLispCompiler.FILE_MODES_RUNTIME_CLASS_FILES` | `%file-access` / `%file-space` / `%file-set-mode` / `%file-make` / `%file-unix` / `%file-set-owner` (the Java subset's permission bits, owners and disk space) | `RontoFileModes` -- the interpreter answers through the same class (`.kb/java-frontend.md`, "What l68 added") |
| `JvmIoRuntimeBuilder.RUNTIME_CLASS_FILES` | an `open` that can ask for `:direction :io` / `:if-exists :overwrite` (`LispMacroExpander.opensBidirectionally`, `.kb/read-load-streams.md`) | `RontoIoFileStream` -- the interpreter runs the same class |
| `JvmJavaImplementations.RUNTIME_CLASS_FILES` | `java:handle` or `java:view` (`JvmLispCompiler.needsJavaValueRuntime`, `.kb/java-interop.md` "Handles and views") | `RontoJavaValue`, `RontoJavaCalls`, `RontoJavaHandle`, `RontoJavaNumberHandle` -- the interpreter makes the same classes |
| `JvmJavaImplementations.VIEW_RUNTIME_CLASS_FILES` | `java:view` (`needsJavaViewRuntime`) | the four `RontoJava*View` classes |
| `cli/RunTimeCompilerFiles` (added by `JvmSourceCompiler`, not the backend) | a Clojure program that lowers as it runs (a host, `.kb/jvm-run-time-lowering.md`) | every class and resource of the build that compiled it -- the compiler the class hands its lowering verbs to; none from a native image, which warns |

Path: `JvmRuntimeClassFiles.read` -> `JvmLispCompiler.runtimeClassFiles()` -> `RontoLispCli`
(beside `-o X.class`, INSIDE `-o X.jar`) and `LispSourceSet` (plugin, `target/classes`);
`resource-config.json` covers the native binary. The same map carries a program's own
`Name$PartN.class` files when its pool outgrew one class (`.kb/jvm-method-size-limits.md`),
keyed in the class's own package rather than at a canonical name (a default-package part has
no directory, which the CLI's writer allows for), and so does a shipped template bridge
(`Name$JavaBridge.class`, `Name$GeomBridge.class`, `.kb/template-class-embedding.md`). A split keeps the exports and the defuns
behind them in the class itself, so the Java API does not move. **The price**: a `runtime` class imports
nothing at all, not even `@Nullable` — so `RontoHashTable.get` takes the absent value and
`RontoHttpServer` nests its own `ServerException`. ONE exception, the war row's
`jakarta.servlet`, `provided` (`.kb/http-server.md`).

## Two consequences of declaring an export

- **Tree-shaker root, the third liveness source** beside `main` and the dispatchable-funcId
  set ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)): the wrapper's
  name joins the shake's roots with `main` and `_apply`/`handle`/`run`/`call`, and
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

`--no-main` drops `main` (`JvmLispCompiler.noMain(boolean)`, a flag in `CliOptionTable` +
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
  run with no coordinate flags; `<dependencies>` lists the `--java-dep` coordinates and is
  `<dependencies/>` (EMPTY, not omitted) without any; `--emit-pom` writes the pom beside the
  jar, guarded against `MavenCoordinates.POM_MARKER`. A program jar's manifest carries
  `Class-Path` into `<stem>-lib/` (`.kb/java-interop.md`, "The program's Java class path").

## `rontolisp-maven-plugin` — `src/main/lisp` as a source set

`src/main/lisp/com/acme/Kernels.lisp` beside `src/main/java`, one jar out. Own module outside
the root reactor, on the rontolisp artifact by coordinates; goals `compile`/`testCompile`, a
parameter per JVM-reaching CLI flag under the same name.

The user-facing description of every goal and parameter is one page,
`doc/en/compiling/maven-plugin.md` (and its `doc/ja` mirror), summarized by
`rontolisp-maven-plugin/README.md`; other pages only link to it. A new or changed
`@Parameter` updates the page's table and, for a main one, the README.

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
  `cli/CompileDiagnostics` reaches Maven as a `MojoFailureException` -- so does
  `warningsAsErrors`' `WarningsAsErrorsException`, at the first file that warns, the files
  compiled before it already written (the same as a compile error) ([compile-warnings.md](compile-warnings.md)).

## The plugin's `wasm` goal -- `src/main/java` to WebAssembly (2026-11-19, k09)

`<mainClass>app.Main</mainClass>` is the program `rontolisp src/main/java/app/Main.java -o
target/<finalName>.wasm` compiles, at `package` (javac and surefire keep the same sources on a
JDK; the module is one more artifact). A goal of this plugin, not a new one (user, 2026-11-19).
With no `<mainClass>` (l85, 2026-11-24) the entry is the first file of the tree, in path order,
that writes `@WitExport`, and the program is the classes it marks, a component's entry points
([java-frontend.md](java-frontend.md), "WIT exports").

- **One code path with the CLI**: `cli/WasmSourceCompiler` is the wasm half the CLI's `-o
  x.wasm` / `--native` used to hold inline -- the flag refusals (`refuseConflicts`, the
  `--native` flag list included; the `-o` extension check stays the CLI's), `compileProgram`
  (`--no-gc` or wasm-GC, the serve/wit-world refusal) and `Result.write` (the module or the
  native executable, `.wit`, the `.js` glue guard). The CLI holds one for its flags; its embedded
  `compile(source, entryFile)` runs the front end as `JvmSourceCompiler` does. Pinned by
  `JvmSourceCompilerTest#theEmbeddedWasmCompileIsByteIdenticalToTheCommandLines` (a two-package
  Java program, `--component --emit-wit`).
- **Parameters** are the wasm flags camel-cased; `native` is `@Parameter(alias = "native")` on a
  field that cannot carry the keyword. No `noGc`: the Java front end refuses it. Output default
  `${project.build.directory}/${project.build.finalName}.wasm`, no extension under `native`.
- **A project's dependencies are its source path** (k32, 2026-11-20; `java-frontend.md`, "A
  library from the source path"): `requiresDependencyResolution = COMPILE`; each artifact of
  scope `compile`, `provided` or `system` that goes on a class path is asked again with the
  classifier `sources` through the build's `RepositorySystem` and the project's remote
  repositories (`WasmMojo.sourcePath`), in the class path's order, after `<javaSourcePath>`'s
  directories and jars (`--java-source-path`'s place on the command line). One with none is a
  `[WARNING] no sources jar for g:a:jar:v`, never a failure: javac needs only the class file and
  the program may never reach it. A dependency's test and optional ones are not in Maven's
  graph; the project's own optional one is, as on javac's class path.
  `WasmSourceCompiler.javaSourcePath` carries the roots, as `JvmSourceCompiler`'s does
  (`JvmSourceCompilerTest#theEmbeddedWasmCompileIsByteIdenticalToTheCommandLines` has a
  `--java-source-path` directory). Measured 2026-11-20: YAVI's schema example
  as a
  project depending on `am.ik.yavi:yavi:0.18.0`: `mvn -o package` 17.3 s, 1,216,108 B (the
  CLI's size), the JDK's 23 lines under `wasmtime`; the second build 2.6 s, up to date.
- **Staleness is all-or-nothing over every `.java` of the tree** (`maven/WasmProgram`): only the
  compile knows which files the program reads. The status file is per output
  (`target/rontolisp/<output name>.status`, so two executions -- Preview 1 and `--component` --
  do not share one) and holds the configuration line (the compiler's version and git commit
  included, so a new SNAPSHOT rebuilds) plus the sorted source list and each source-path root
  (`sourcePath <absolute>`, a directory's `.java` files under it); every listed file and jar is
  stamped against it, stamped before the work. A missing `<javaSourcePath>` entry fails as
  `--java-source-path`'s does.
- Any `RuntimeException` (a front-end refusal with `file:line:column`, a flag conflict,
  `WarningsAsErrorsException`) is a `MojoFailureException` with the message verbatim. A class of
  a dependency with no sources jar is refused by name: `@Nullable` is `Main.java:7:9: cannot find
  symbol: class Nullable` (at the use: an import of a class outside the program is resolved where
  it is used, `java-frontend.md`).
- `MavenBuildE2eTest#aJavaProgramBuildsToWasmThatPrintsWhatTheJdkPrints` (needs `wasmtime` on
  PATH; the CI job installs it): the program checks a record with YAVI, whose jar and sources
  jar the module's own test dependencies put in the local repository its offline build reads;
  the refusal leg's dependency is jspecify's jar as a `system` dependency of coordinates no
  repository has, so no machine finds a sources jar for it. Checked by hand 2026-11-19: `examples/java/http/Server.java`
  built with `<component>true</component>` serves under `wasmtime serve` from `target/`,
  and `<native>true</native>` writes a running executable when the installed rontolisp jar carries
  the shim (the CI job's does not build it, so the E2E has no native leg).
- **The Java examples that use a library are Maven projects** (2026-11-23):
  `examples/java/<name>/pom.xml`, each standalone (2026-11-23, l51: no parent; a copied
  directory builds): its own versions as properties, the snapshot repository, the `exec:exec`
  JDK run and a `native` profile (a native execution of the plugin). The war examples set
  `maven-war-plugin`'s `webappDirectory` to `target/war`: `target/<finalName>` is the native
  executable's. `MavenBuildE2eTest#anExampleOfTheCheckoutBuildsToAWarAndAComponentThatAnswerAlike`
  builds the guestbook and serves the war on Tomcat and the component under `wasmtime serve`
  (`session-workflow.md`). The tests that compile an example on the CLI read the pom
  (`running-backends.md`, `ExampleProject`).

## The plugin's `test` goal -- `rontolisp test` at `test` (2026-11-24, l82)

`TestMojo` (Maven) over `TestRuns` (no Maven type): after surefire, the project's tests run
by the command line a user types, `java -cp <the plugin's rontolisp jar>
am.ik.rontolisp.cli.RontoLispCli test ...`, a process a backend in `${basedir}` (surefire's
working directory; an in-process interpreter run would read a relative path against the
directory Maven was started in), and for a compiled backend the artifact after it
(`target/rontolisp/test/...`: `java -cp`, `wasmtime run`). Every line is logged at INFO; a
failed test's name (`... <<< FAILURE!` / `<<< ERROR!`, rove's `Summary:` list) or the
`error:` line is the `MojoFailureException`, every backend run first.

- **Which half**: a project with `src/test/lisp` is a Lisp one -- each file of it whose text
  holds a `deftest` is a run (`rontolisp test` takes one rove target), `--system-path
  src/main/lisp:src/test/lisp` plus `<systemPath>`; any other project's tests are its Java
  ones. Not both: a Lisp source set's `src/test/java` calls the exported classes, which
  only a JVM has.
- **Java: one run a backend, every file at once** (several files of one tree,
  [java-frontend.md](java-frontend.md), "Tests of a program"), the files surefire's
  `<includes>`/`<excludes>` and defaults select (Ant patterns, `TestRuns.glob`). The source
  path is surefire's class path order: `target/test-classes`, `target/classes` (resources),
  the compile and test source roots but the tree, `<javaSourcePath>`, then the sources jar
  of each `compile`/`provided`/`system`/`test` artifact (a warning where there is none).
- **The default backend is the interpreter: measured** (2026-11-24, cold CLI, wall time of
  compile + run): `CounterTest` of `examples/java/rust-calls-java` 2.0 s / 4.5 / 5.5 / 5.7
  (interpreter / JVM / P1 / component), `UrlCodecTest` 2.5 / 5.3 / 6.3 / 6.6, `ScramTest`
  3.0 / 6.7 / 7.8 / 7.9; a rove file of one test 2.9 / 14.1 / 16.8 / 16.6 (rove compiled
  in: 3.7 MB class, 3.1 MB wasm).
- Skipped by `rontolisp.skip`, `skipTests` and `maven.test.skip`; no staleness (surefire
  runs every time).
- `MavenBuildE2eTest#anExampleWithNoMainClassBuildsToTheComponentOfItsWorld` (the example's
  pom has the goal; a failing file of another package on the four backends, `-DskipTests`)
  and `#aRealBuildCompilesTheLispBeforeTheJavaAndJarsBoth` (a rove file, passing then failing).

## Tests

`JvmExportTest` (framing, handle identity, `<clinit>` signalling, shaker roots),
`JvmExportExampleTest` (`examples/jvm/kernels-library.lisp` from Java), `JvmArtifactOptionsTest`,
`RontoLispCliTest`, `JvmSourceCompilerTest#theEmbeddedCompileIsByteIdenticalToTheCommandLines`,
`JvmRuntimeClassFilesTest`, `JvmHttpHandlerTravellingRuntimeTest`, `RontoFloatArrayTest`,
`MavenBuildE2eTest`, `e2e/JarMavenConsumerE2eTest` (`-Drontolisp.jar.e2e=true`), and the no-op
pins in `LispEvaluatorTest` / `WasmLispCompilerIntegrationTest`.
