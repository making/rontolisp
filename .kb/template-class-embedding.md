# Template-class embedding (the `JavaBridgeTemplate` mechanism) is a LAST RESORT

Preference order for a runtime helper in compiled output: (1) macro expansion into existing
primitives; (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`, the standard everywhere else
([[stackmap-augmenter]]); (3) a project-compiled Java class, renamed after the generated program
by constant-pool rewrite (`JvmJavaRuntimeBuilder.renameClass`). Use (3) only for a helper
needing JDK facilities impractical in raw bytecode: [[java-interop]] (only for the sites left to
run time: a resolved site is a hand-assembled direct call, `JvmJavaDirectSites`), [[geom]] and the
`--simd`/`--blas`/`--gpu`, `objc:`, `ffi:` bridges. Before adding one, check whether the complex
part can run at COMPILE time; pin the rename with
`JvmJavaInteropCompilerTest#renameClassLeavesOtherUtf8EntriesIntact`.

**A template SHIPS; it is never `defineClass`d.** The renamed bytes join
`JvmLispCompiler.bridgeClassFiles` -> `runtimeClassFiles()` (beside `-o X.class`, inside
`.jar`/`.war`, the Maven plugin's `target/classes`, the playground's jar). Every bridge is named after
the program, in its package (the entry points are package-private): `<Program>$JavaBridge`,
`$GeomBridge`, `$SimdBridge`, `$BlasBridge`, `$GpuBridge` + `$Gpu<Class>`, `$ObjcBridge` +
`$ObjcObject` + `$Objc<Class>`, `$FfiBridge` + `$FfiPointer` + `$Ffi<Class>`. Named after the
program because `bind` state and the libraries' statics are per program, and two versions never
share a file. `ShippedBridgeClassFilesTest` pins all seven, the absence of `defineClass`/`Base64` in
the class, no pre-rename name left in a shipped file, and no collision with every bridge in one
program.

Why: until 2026-09-26 they were base64 string constants `Lookup.defineClass`d at first use. A GraalVM
native image refuses that with `UnsupportedFeatureError` -- an `Error`, NOT a `LinkageError`, so no
degrade catches it; every such `-o prog.jar` crashed under native-image (measured for java: and geom,
GraalVM 25.0.4; the other five emitted the same sequence). `ShippedBridgeNativeImageE2eTest` (opt-in)
now builds java:, geom:, `--simd` (with and without `--add-modules jdk.incubator.vector`), `--blas`,
`--gpu` and `ffi:` jars into images: java:/`--blas`/`ffi:` with the config the tracing agent records
from one `java -jar` run (it records FFM downcalls under `foreign`), the rest with none -- `--gpu`
because it ships its own downcall registration: `am/ik/gpu/reachability-metadata.json` travels in
`runtimeClassFiles()` to `META-INF/native-image/rontolisp-gpu/<program>/`, where native-image reads
it from the jar or class directory. Without it (until 2026-09-27) the image built and printed the
right answers while refusing the CUDA binding, so every member ran on the CPU -- visible only on a
machine with a device (found on the GB10). `aGpuJarTakesTheDevicePathUnderJavaJarAndAsANativeImage`
pins it by memory (48 lazy 16 MB results under `-Xmx256m`, which the CPU path cannot hold). On macOS
it builds an `objc:` jar too, whose `main` hands thread 0 to the run loop, with NO configuration since
2026-09-27: the jar carries the `rontolisp-objc` foreign registration and a per-program reflection
entry for `_apply`/`_strv` under `META-INF/native-image/` ([[objc]], "The JVM backend").

What the emitted `_*Init` does now: `_javaInit`/`_objcInit`/`_ffiInit` only `bind`; `_gpuInit` hands
the kernel texts over; `_geomInit` `ldc`s its bridge inside a `LinkageError` catch; `_simdInit` forces
link + init (`Lookup.ensureInitialized`) inside its catch -- a class constant only LOADS a class, it
links at first use, so an `ldc` alone moves the missing-incubator failure to the first kernel call
([[linalg-simd]]); `--blas` has no init at all.

A test that loads compiled output must write `runtimeClassFiles()` beside the class
(`TravellingClassFiles.write` in `codegen/jvm` tests). A shipped bridge that degrades on
`LinkageError` (geom, simd) degrades when the file is missing too -- geom silently, simd with the
incubator warning -- so an oracle test must also assert the bridge class loaded
(`JvmGeomKernelCompilerTest.run`).

**The one flagless template is `JvmGeomTemplate`** ([[geom]]): emit gate is a CALL-SITE scan of the
PRUNED program (a program calling no member stays byte-identical), and `_geomInit` catches the
`LinkageError` loading the bridge can raise, so an older JRE degrades to the defuns.

## Demerits

- Raises the output's JRE floor silently -- the template carries the project's class version (Java
  25) against the version-61 "Java 17+" baseline.
- The output is more than one file, and a class copied alone loses its bridge.
- Invariants javac cannot check: no nested classes/records in a single-file template (lambdas are
  fine) -- the builder ships the template's own file only; no imports of other rontolisp classes;
  written against the compiled value representation and kept in sync with its interpreter twin by
  hand.
- Reflection back-calls need `setAccessible`; the template `.class` must be in
  `resource-config.json` (the native CLI reads it to compile).

## Class closures (`--gpu`/`am.ik.gpu` [[gpu]], `objc:` [[objc]], `ffi:` [[ffi]])

A whole library ships: ONE prefix rename over every file (`am/ik/gpu/` -> `<Program>$Gpu`) carries
nested classes without naming them, so the glue template is written against the real library and
type-checked by javac. A `getResourceAsStream` resource cannot follow and travels as its own string
constant in the program's pool through a public entry point (the `--gpu` PTX/MSL).

- **Required guard: a test pinning the shipped class LIST against the package's actual class files**
  -- nothing can enumerate a package from a classpath, still less in a native image.
- The list's order is free: every file is on disk before any loads. (It was not while each was
  `defineClass`d in turn -- the verifier loads a `catch` type while defining the class that has it,
  so `ObjcException` had to go first.)
- **A library may call back into the program** through `bind(Class)`, which forces the eval runtime
  and roots `_apply` for the shaker.
- An enum `switch` in a template lowers to a synthetic `$1` class the builder does not ship -- use an
  if-chain; a test pins the absence of `$` siblings.
