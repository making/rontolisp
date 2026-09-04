# `rontolisp:jvm-export` (typed Java-callable methods) + `--no-main` library mode

`(rontolisp:jvm-export 'name :params '(T...) :returns T :as "javaName")` is the JVM twin of
`rontolisp:wasm-export` ([wasm-export-no-wasi.md](wasm-export-no-wasi.md)): it declares the
Java-boundary types of a top-level `defun` so `JvmLispCompiler` emits a thin `public static`
wrapper with a primitive/`String`/`byte[]`/handle signature next to the untyped
`(Object...)Object` method.

- Directive parsing + Java-name derivation: `compiler.JvmExportDirective` (backend-free, the
  `WasmImportDirective` arrangement). Bytecode: `codegen.jvm.JvmExportRuntimeBuilder`.
- No-op everywhere else, so one source runs on all four backends: the interpreter defines it as
  a return-the-named-symbol function (`Environment`, beside wasm-export's), and both WASM
  top-level collectors skip the form (`WasmLispCompiler` / `NoGcWasmCompiler`, exactly where
  they collect wasm-export).
- **A program with no jvm-export compiles byte-identically to before the feature** — every
  mechanism here is gated on `!exportDecls.isEmpty()` (or on `--no-main`, which requires it),
  so the flagless corpus (`ci-spec.yaml`, `.kb/emitted-output-determinism.md`) is untouched.
- Docs: `doc/{en,ja}/guides/jvm-library.md`, `reference/functions/rontolisp-jvm-export.md`.

## The Java type mapping, and the exact-or-throw rule

`BoundaryType` is the shared vocabulary (same designators as wasm-export;
`JvmExportRuntimeBuilder.javaDesc`):

| designator | Java type | guard |
| --- | --- | --- |
| `:s8` `:s16` `:s32` `:s64` | `byte` `short` `int` `long` | none inbound (ranges coincide); result guarded |
| `:u8` `:u16` | `int` | `_exArg` inbound, `_exRes` outbound |
| `:u32` `:u64` | `long` | same; `:u64` >= 2^63 is unrepresentable in the signed-64 house integer and throws, the WASM boundary's trap |
| `:float` | `double` | result via `checkcast Number; doubleValue` (integer/ratio-as-Number accepted, like wasm's `castFloatGetF64`) |
| `:bool` | `boolean` | `false`=nil (`null`), `true`=`"T"`; any non-nil result is `true` |
| `:string` | `String` | wrapper adds/strips the FRAME QUOTES storage carries |
| `:s-expr` | `String` | in: frame + `_readFromString`; out: `_lispToString` |
| `:bytes` | `byte[]` | copies to/from the packed octet vector `long[]{8, e0, ...}` |
| `:float-vector` `:float-matrix` | `RontoFloatArray` | JVM-ONLY (`jvmOnly()`); ALIASES the packed float array, rank checked |

Rule is wasm-export's verbatim: **the boundary carries the value exactly, or it throws** —
`IllegalArgumentException` for an argument outside its declared range, `ArithmeticException`
for a result outside it, `ClassCastException` for a result of the wrong representation
(including a BARE string, which is a SYMBOL — `_exStr` refuses it rather than conflate the two
`.kb/core-representation.md` encodings).

- **`:string` framing must not regress**: a Lisp string stores its frame quotes, so an unframed
  Java `String` passed to the untyped method is silently mis-read (`GREET("ron")` answered
  `"hello, o"`). The wrapper frames in and unframes out; pinned by
  `JvmExportTest#aStringExportFramesOnTheWayInAndUnframesOnTheWayOut`.
- An `:s-expr` parameter forces the reader runtime on
  (`usesRead |= JvmExportRuntimeBuilder.needsReader`), with read-from-string's full consequences
  (`anyNameResolvable` -> every funcId dispatchable).

## The packed float array (`:float-vector` / `:float-matrix`)

The two designators the JVM boundary has and WASM does not: a packed float array
([vec.md](vec.md)) crossing as `am.ik.rontolisp.runtime.RontoFloatArray`, ONE handle class at
every rank and width. `BoundaryType.FLOAT_VECTOR` / `FLOAT_MATRIX` carry `jvmOnly()`, which is
what makes `WasmExportCompiler.typeDesignator` refuse them BY NAME (pinned by
`WasmLispCompilerIntegrationTest#theJvmOnlyHandleDesignatorsAreRefusedByNameOnWasm`) instead of
failing later in a component lift.

- **The wrapper ALIASES in both directions** — `RontoBoundary.floatArrayArgument` hands over
  `handle.packed()`, `floatArrayResult` wraps the answered array — because a facade that copies
  a `double[]` per call is ~10x the kernel and lands slower than plain Java. The only copies are
  the two the caller asks for: `of(...)` in, `toArray()` out. Pinned by object identity in
  `JvmExportTest#aHandleHeldAcrossCallsCopiesOnce`. Aliasing is the CONTRACT, not an
  implementation detail: `set(i, v)` through a handle a kernel returned is visible to a Lisp
  closure over the same array and vice versa; nothing is defensively copied.
- **Both widths, any rank.** `double[]` and `float[]` are disjoint representations and a third
  is coming, so `RontoFloatArray` dispatches width in ONE private place (`widthOf`/`headerAt`)
  and reports it as `Width`, an enum a caller must not assume has two members. Rank comes from
  the header, so a matrix is the same class with a rank-2 `dims()`; the designator says which
  rank the boundary accepts and a mismatch throws there (`IllegalArgumentException` inbound,
  `ClassCastException` outbound).
- A declared handle forces `usesFloatArray` on in `JvmLispCompiler`: a library whose only
  contact with the representation is `aref`/`length` over its argument builds no packed array of
  its own and would otherwise be emitted without the `_fv*` accessors.
- **Where the handle type comes from**: the class files are copied VERBATIM at their canonical
  names next to the output class — not renamed per program the way the acceleration bridges
  travel ([template-class-embedding.md](template-class-embedding.md)), because a renamed
  boundary TYPE would give two rontolisp libraries incompatible vector types and no way to feed
  one's result into the other's kernel. One canonical name makes chaining work while the jar
  keeps no dependency; identical bytes make the duplicate harmless.

## What travels

`am.ik.rontolisp.runtime` is THE package that ships inside someone else's artifact:

| list | travels when | what it is |
| --- | --- | --- |
| `JvmExportRuntimeBuilder.RUNTIME_CLASS_FILES` | a `:float-vector` / `:float-matrix` export | `RontoFloatArray` + `RontoBoundary` |
| `JvmHttpHandlerRuntimeBuilder.RUNTIME_CLASS_FILES` | `rontolisp:http-handler` / the `%http-server-start` seam | `RontoHttpServer` (embedded server, shared with the interpreter), `RontoHttpClack` (per-request Clack glue), `RontoClackEnv` + `RontoHashTable` (`.kb/http-server.md`) |
| `JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES` | a `.war` output, or the plugin's `<servlet>true</servlet>` (IN ADDITION to the served list) | `RontoHttpServlet` + `RontoHttpServletInitializer`, the Servlet transport (`.kb/http-server.md`, "The fifth transport") |
| `JvmHashRuntimeBuilder.RUNTIME_CLASS_FILES` | a `(make-hash-table :test 'equalp)` in the source | `RontoHashTable` again, for `equalpKey` (`.kb/hash-tables.md`) |

- Path: `JvmRuntimeClassFiles.read` -> `JvmLispCompiler.runtimeClassFiles()` -> `RontoLispCli`
  (beside a `-o X.class`, INSIDE a `-o X.jar`) and `LispSourceSet` (the Maven plugin, into
  `target/classes` before javac would run); `resource-config.json` matches the whole package by
  pattern so the native binary carries them too. A served program is therefore self-contained —
  `java -cp . App`, no rontolisp jar (promised by `doc/{en,ja}/guides/http-handler.md`).
- **The price**: a class in `runtime` imports nothing at all, not even the build's `@Nullable`
  (`RuntimeVisible`, so its reference would follow the class into the consumer's artifact).
  Hence `RontoHashTable.get` takes the absent value instead of answering null,
  `RontoHttpServer.Request` spells "unknown" as `""`, and `RontoHttpServer` raises its own
  nested `ServerException` which the interpreter's call site turns back into a
  `LispEvalException`.
- The ONE stated exception is the war row: `RontoHttpServlet`/`RontoHttpServletInitializer`
  import `jakarta.servlet`, satisfied by definition (a war runs in a container), `provided`
  scope in the pom, never in any other artifact. The war-mode arm of
  `JvmHttpHandlerTravellingRuntimeTest` admits `jakarta/servlet/**` and keeps failing for any
  other outside reference.
- Each travelling list is hand-kept (nothing can enumerate a package from a classpath, less so
  from inside a native image). `JvmRuntimeClassFilesTest` pins their UNION against the package's
  actual class files; `JvmHttpHandlerTravellingRuntimeTest` recomputes the served closure from
  the emitted class's own constant pool and serves it through a class loader that cannot see
  rontolisp. `package-info.class` deliberately stays behind (it carries only the nullness
  annotation).

## `--gpu` residency: the handle does not materialize

The JVM class output has no read seam and ENUMERATES its readers through `_gpuMaterialize`
([gpu.md](gpu.md), "The two seams, and what must report through them"); a handle's
`get`/`set`/`toArray` are new readers outside that enumeration. Materializing at the boundary
would defeat the lazy tier (a result the device still holds would come home only to be
re-uploaded).

- So the handle is wrapped WITHOUT materializing and carries the guard: it adopts the generated
  class (the wrapper passes `ldc thisClass`), resolves that class's private `_gpuMaterialize` /
  `_gpuWritten` once through `MethodHandles`, and reads/writes what the guard ANSWERS — the
  array, or a lazy result stub's backing, since a lazy result's host array is the HEADER ALONE
  (hence `checkPacked` requires `1 + rank` elements and not one more).
- A class with no guards resolves to a marker and costs one reference comparison.
- Pinned by `RontoFloatArrayTest#aHostReadGoesThroughTheOwnerClassResidencyGuard` with a
  stand-in owner class, so the seam is exercised with no device.
- Measured on CUDA (`examples/jvm/bench/`, `./run.sh gpu`): the Java chain runs at the same
  per-iteration cost as the same chain inside Lisp, with ONE upload for the whole run where a
  materializing boundary would have paid one per iteration; `toArray()` moves the result home
  exactly once and answers the no-`--gpu` build bit for bit.
- On Metal lazy results are OFF as a measured policy ([gpu.md](gpu.md), "Lazy results and the
  resident tier on Metal"), so the guard is idle there except for `set` into a resident matrix,
  which invalidates the device copy. `floatArrayResult` still does not materialize.

## Exports are tree-shaker roots — the third liveness source

Each wrapper's Java name joins `JvmClassShaker`'s roots next to `main` and the invisible-edge
roots (`_apply`, `handle`, `run`, `call`): its caller is Java code the bytecode cannot show.
This is the directive's whole reason to exist under `--optimize` (ON by default) — without a
root, a library's defuns are unreachable from `main` and shaken away. Third liveness source
beside `main` and the dispatchable-funcId set of
[optimize-dead-code-elimination.md](optimize-dead-code-elimination.md); the wrapper's
`invokestatic` of the target defun keeps the defun and its graph. Pinned by
`JvmExportTest#anExportedDefunSurvivesTheDefaultOptimizeWhileAnUnexportedOneIsShaken`.
(`--no-prune` is the AST library-splice pruner, a different mechanism; a jvm-export form's
quoted symbol already counts as a mention there.)

## The top level runs in `<clinit>` (the reactor precedent)

`defvar`/`defparameter` initialization lives in `_top$0..N`, which only `main` called — so a
typed call arriving first read `null`. With any export, the chunk calls (plus the raw-octet
`System.out.flush()` epilogue and the `JvmUncaughtHandler` exception table) move into a
`_top$run` method that `<clinit>` invokes LAST, after the ThreadLocal/stream/layout/struct
seeding; `main` (when kept) emits only `return`. Invoking it triggers `<clinit>` first (JVMS
class initialization), and the JVM's own init locking is the idempotence, so there is no
`_inited` guard on either path. This is the cross-backend answer: the `--no-wasi` reactor runs
its top level at instantiation, and `<clinit>` is the JVM's instantiation.

Two consequences, documented rather than designed around (both true of the reactor too):

- A top-level form that signals surfaces as `ExceptionInInitializerError` — after `_top$run`'s
  uncaught handler prints the one-line report — and poisons the class permanently
  (`JvmExportTest#aTopLevelThatSignalsSurfacesAsExceptionInInitializerErrorOnTheFirstTypedCall`).
- A top-level `uiop:quit` kills the caller's JVM.

## `--no-main`

Library mode on the `--no-wasi` precedent: drop `main`, the class is entered through exports
only. `JvmLispCompiler.noMain(boolean)`; `CliOptions.noValueKeys` + `RontoLispCli` route it,
refusing a non-JVM output, a jar with no `--class-name`, and (in the compiler, so an embedder
gets it too) a program with no jvm-export — `main` is the only shake root such a program has, so
a main-less export-less class would shake to nothing. Kept ORTHOGONAL to the directive: a
program may want both a `main` and exports, and only the flag says which. It also decides a
jar's `Main-Class`. `-o com/acme/Kernels.class` creates missing parent directories
(`RontoLispCli`), since the `-o` path IS the package.

## `-o out.jar` — the consumable artifact

`.class` and `.jar` are the SAME compile; the jar is packaging around it (`cli/JvmJarWriter`,
`cli/MavenCoordinates`, `cli/JvmArtifactOptions`). Every flag that used to test "the JVM class
output only" (`--blas` / `--gpu` / `--parallel` / `--no-main`) now tests
`RontoLispCli.jvmOutput`, which is the two extensions.

- **A `.class` path's directory is the PACKAGE only when it can be one**
  (`JvmArtifactOptions.classNameFromClassPath`). The test is the JVM's, not javac's — JVMS
  4.2.2 asks each segment to be non-empty and free of `. ; [ /`, nothing more; deliberately
  WIDER than a Java identifier, so `-o out-dir/T3.class` emits `out-dir.T3` and runs. What it
  catches is a path for which a package was never plausible: an ABSOLUTE path opens the name
  with an EMPTY segment (`ClassFormatError: Illegal class name "/tmp/out/T2"`), and `./`/`../`
  put a `.` inside one — there the file's stem is the whole name, and `classRoot` roots the
  travelling runtime classes at the directory so `java -cp /tmp/out T2` runs. A stem that
  cannot be a class name either (`out/my.prog.class`) is REFUSED naming `--class-name`, which
  is also how an absolute path still names a package. **Silence was the whole cost**: the
  compile used to report success and only running the artifact reported the illegal name (two
  builds into two temp directories then differ in every byte, which reads as a regression).
  Pinned by `JvmArtifactOptionsTest` (every arm) and
  `RontoLispCliTest#anAbsoluteOutputPathEmitsALoadableClass` /
  `#anAbsoluteOutputPathTakesItsPackageFromClassName`, which LOAD the emitted class through a
  `URLClassLoader`.
- **`--class-name` is required by the LIBRARY jar, not by jar output.** A `--no-main` library's
  class is the artifact's Java API and the caller's `import`, so `RontoLispCli` refuses that jar
  without the flag; a program jar is entered through the manifest, so
  `JvmArtifactOptions.classNameFromStem` DERIVES one from the file's stem — split on everything
  a Java identifier cannot hold, rejoined CamelCase (`app.jar` -> `App`, `my-app-1.0.0.jar` ->
  `MyApp100`, a digit-leading stem prefixed `_`). Sanitizing is load-bearing: a `.` left in a
  stem reads as a package separator, i.e. a `Main-Class` that does not resolve. Given, the flag
  also replaces the path-derived name on a `.class` output; `JvmArtifactOptions.classRoot` then
  roots the travelling runtime classes at the package root when the `-o` path still ends in the
  package path, and beside the output file when it does not. Pinned by
  `RontoLispCliTest#aProgramJarNeedsNoClassNameAndIsExecutable` (really runs `java -jar`),
  `#aProgramJarsClassNameIsItsFileNameInCamelCase`,
  `#aLibraryJarStillNeedsAClassNameBecauseItsClassIsItsApi`.
- **Entries, in this fixed order**: the manifest (`Main-Class` exactly when the class HAS a
  main), the `META-INF/maven` pair when `--maven-coordinates` is given, the class at its package
  path, then `JvmLispCompiler.runtimeClassFiles()` at their canonical names. **The runtime
  classes are the trap**: leaving them out is a `NoClassDefFoundError` in the CONSUMER, not an
  error here — pinned by
  `RontoLispCliTest#aLibraryJarIsSelfContainedOnAClasspathThatCarriesNothingOfRontolisp`, which
  loads the jar under the PLATFORM loader.
- **The embedded pom IS the feature**: with
  `META-INF/maven/<groupId>/<artifactId>/pom.xml` + `pom.properties` present,
  `mvn install:install-file -Dfile=out.jar` installs at the right coordinates with no
  `-DgroupId`/`-DartifactId`/`-Dversion`/`-DpomFile`, and Maven writes the embedded pom beside
  the artifact rather than a stub. Pinned end to end by `e2e/JarMavenConsumerE2eTest` (opt-in
  `-Drontolisp.jar.e2e=true`; it installs into the developer's REAL local repository under a
  test-namespaced groupId and deletes it afterwards).
- `<dependencies/>` is written EMPTY rather than omitted: emptiness is the property that makes
  the artifact trivial to consume. The `<description>` carries the `--simd` ->
  `--add-modules jdk.incubator.vector` note, since a module-less JVM degrades to the scalar
  kernels rather than failing (so the flag is worth PASSING, not required) and the consumer
  never saw the build command.
- **A jar is emitted output** ([emitted-output-determinism.md](emitted-output-determinism.md)):
  one fixed DOS timestamp via `setTimeLocal` — `setTime(long)` converts through the default zone
  and adds an extended-timestamp extra field, so the same build would differ between machines —
  and a fixed entry order, the runtime classes SORTED because `runtimeClassFiles()` answers a
  `Map.copyOf` whose iteration order is per-process random.
- `--emit-pom` writes the same pom beside the jar (the `--emit-wit`-next-to-the-`.wasm`
  precedent) with `--emit-js-glue`'s refuse-to-overwrite guard against
  `MavenCoordinates.POM_MARKER` (version-free, so an upgrade still recognizes the previous
  release's pom).
- **Not ours: `install` / `deploy`** — `install-file` / `deploy-file` already do it.

## `rontolisp-maven-plugin` — `src/main/lisp` as a source set

The other entry point of the same story, and the PRIMARY one: one `<plugin>` block,
`src/main/lisp/com/acme/Kernels.lisp` beside `src/main/java`, and `mvn package` produces one jar
with both. Its own module (`rontolisp-maven-plugin/`, outside the root reactor like
`docs-tool/`, depending on the rontolisp artifact by coordinates), goals `compile` and
`testCompile`, one parameter per JVM-reaching CLI flag under the same name.

- **The path IS the class name** (`src/main/lisp/com/acme/Kernels.lisp` -> `com.acme.Kernels`);
  a path segment that is not a Java identifier is refused by name. Checked only for a file that
  earns a class, so `string-utils.lisp` beside `Kernels.lisp` is not an error.
- **`process-sources`, not `compile`, and this is measured**: the classes the goal writes (the
  kernel class, and the `am.ik.rontolisp.runtime` handle a `:float-vector` export hands out) are
  what `src/main/java` compiles AGAINST. Maven's model builder merges lifecycle-injected plugins
  AHEAD of POM-declared ones, so a goal bound to `compile` runs AFTER
  `maven-compiler-plugin:compile` and the sample project fails with `package com.example does
  not exist`. `process-sources` / `process-test-sources` is the only ordering declaration order
  cannot break (what `kotlin-maven-plugin` documents for the same reason). `MavenBuildE2eTest`
  is the pin, and it is a REAL Maven build — nothing else sees a phase-ordering regression.
- **A source set is Lisp, not a pile of exports**: files load each other, most have no Java
  caller. A file compiles to a class exactly when the class would have an ENTRY POINT — `noMain`
  defaults to TRUE (the CLI's flag does not), the entry point is the exports, and a file
  declaring none is left as Lisp (spliced into files that `(load ...)` it, or run by the
  interpreter) rather than failing the build. `<noMain>false</noMain>` gives every file `main`.
  `JvmSourceCompiler.compileIfExported` is the seam and asks the EXPANDED program, so an export
  contributed by a `(load ...)`ed file or a user macro counts.
- **`<servlet>true</servlet>` is `-o app.war`'s mode** through the same mojo parameter shape. It
  sets `JvmSourceCompiler.servlet`, which makes `runtimeClassFiles()` add
  `WAR_RUNTIME_CLASS_FILES` to what `LispSourceSet` writes into `target/classes`, and the goal
  writes the one file that loop cannot — the
  `META-INF/services/jakarta.servlet.ServletContainerInitializer` line naming
  `RontoHttpServletInitializer`, `maven-war-plugin`'s only non-class input, so a war built from
  `target/classes` needs no `web.xml`. It also forces `noMain` off for the execution regardless
  of the parameter: a war has no `main`, so every file compiles unconditionally and each must
  carry its own `rontolisp:http-handler` (or the internal `%http-server-start` seam) —
  `JvmLispCompiler`'s `servletMode && !usesHttpHandler` check (`.kb/http-server.md`) is what a
  file lacking one fails against, so **shared code has to be a `(load ...)`ed file rather than a
  sibling `.lisp` under the source directory**. `servlet` and `${project.packaging}` are
  cross-checked in `CompileMojo` (not `TestCompileMojo`, whose classes are never packaged into a
  war); the two messages name which of `<packaging>war</packaging>` / `<servlet>true</servlet>`
  is missing, so the defect is a build failure rather than a war that 404s.
- **Staleness is all-or-nothing** (`maven-compiler-plugin`'s own rule): a `(load "...")` splices
  one source into another, so a file whose own timestamp did not move can still need
  recompiling. State comes from a STATUS FILE (`target/rontolisp/compile-status.txt`, source
  path -> class name or `-`) rather than the output directory, since a source set whose files
  need not each produce a class cannot ask the output directory whether a missing class was
  skipped or never built; a recorded class since deleted, and an added or removed source, both
  make it stale. A runtime class is rewritten only when its bytes differ.

### The seam: `cli/CompileFrontend` + `cli/JvmSourceCompiler`

The plugin compiles IN PROCESS: the front end is where the work is. `cli/CompileFrontend` is
the whole of it (the read with the target's feature set, the `(load ...)` inlining, user-macro
expansion, the library splice chain, the WIT lowerings, the `boundp` fold, the library
tree-shaker), in ONE order-critical place that all four backends and every embedder share.
`cli/JvmSourceCompiler` is the public embedder entry point: fluent like `JvmLispCompiler`,
source text in, class bytes plus travelling runtime classes out, nothing written to disk.
`RontoLispCli`'s `-o out.class` / `-o out.jar` path runs the SAME backend half
(`compileProgram`) — pinned by
`JvmSourceCompilerTest#theEmbeddedCompileIsByteIdenticalToTheCommandLines`. Diagnostics are
shared (`cli/CompileDiagnostics`), so an embedder's failure carries the same
`file:line:column:` prefix, handed to Maven verbatim as a `MojoFailureException`.

**Its release rides with the core's**: the version scripts (`set-release-version.sh`,
`set-next-{patch,minor}-version.sh`) set the module's version and its `rontolisp.version`
property together, and CI deploys it right after the core.

## Validation (compile time, in `JvmLispCompiler` after Pass 1)

- unknown / non-defun target, arity mismatch — wasm-export's checks, same wording;
- a variadic target (`&optional`/`&rest`/`&key` desugars to variadic) is refused: no fixed Java
  signature;
- the wrapper name must be a Java identifier and non-keyword
  (`JvmExportDirective.isJavaMethodName`; default derivation is the Lisp member
  lower-camel-cased, `:as` overrides), must not start with `_` (the generated runtime's
  namespace) or be `main`, and must not equal another export's name or any MANGLED defun name —
  a duplicate method name is the `ClassFormatError`-at-load family
  `.kb/core-representation.md` records for redefined defuns.

## Tests

`JvmExportTest`, `JvmExportExampleTest` (compiles `examples/jvm/kernels-library.lisp` and calls
it from Java), `JvmArtifactOptionsTest`, `RontoLispCliTest`, `JvmSourceCompilerTest`,
`JvmRuntimeClassFilesTest`, `JvmHttpHandlerTravellingRuntimeTest`, `RontoFloatArrayTest`,
`MavenBuildE2eTest`, `e2e/JarMavenConsumerE2eTest` (`-Drontolisp.jar.e2e=true`). Cross-backend
no-op pins: `LispEvaluatorTest#jvmExportIsNoOpReturningTheNamedSymbol`,
`WasmLispCompilerIntegrationTest#jvmExportDirectiveIsANoOpOnWasm`.
