# Template-class embedding (the `JavaBridgeTemplate` mechanism) is a LAST RESORT

Preference order for a runtime helper in compiled output: (1) macro expansion into existing
primitives; (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`, the standard everywhere else
([[stackmap-augmenter]]); (3) a project-compiled Java class, renamed into the generated program's
package by constant-pool rewrite (`JvmJavaRuntimeBuilder.renameClass`). Use (3) only for a helper
needing JDK facilities impractical in raw bytecode: [[java-interop]], [[geom]] and the
`--simd`/`--blas`/`--gpu`, `objc:`, `ffi:` bridges. Before adding one, check whether the complex
part can run at COMPILE time; pin the rename with
`JvmJavaInteropCompilerTest#renameClassLeavesOtherUtf8EntriesIntact`.

**A new template SHIPS; it is never `defineClass`d.** Two ways to deliver the renamed bytes:

- **Shipped** (java:, geom): `<Program>$<Name>Bridge.class` joins `JvmLispCompiler.bridgeClassFiles`
  -> `runtimeClassFiles()` (beside `-o X.class`, inside `.jar`/`.war`, the Maven plugin's
  `target/classes`); the init only `bind`s or `ldc`s. Named after the program: `bind` state is
  per-program, and two versions never share a file.
- **Embedded** (simd, blas, gpu, objc, ffi -- to be migrated, see `.todo/`): base64 string constants
  `Lookup.defineClass`d at first use. A GraalVM native image refuses that with
  `UnsupportedFeatureError` -- an `Error`, NOT a `LinkageError`, so no degrade catches it. Measured
  2026-09-26 (GraalVM 25.0.4): every java: and geom jar crashed under native-image this way.

A test that loads compiled output must write `runtimeClassFiles()` beside the class. A shipped
bridge that degrades on `LinkageError` (geom) degrades SILENTLY when the file is missing, so an
oracle test must also assert the bridge class loaded (`JvmGeomKernelCompilerTest.run`).

**The one flagless template is `JvmGeomTemplate`** ([[geom]]): emit gate is a CALL-SITE scan of the
PRUNED program (a program calling no member stays byte-identical), and `_geomInit` catches the
`LinkageError` loading the bridge can raise, so an older JRE degrades to the defuns.

## Demerits

- Raises the output's JRE floor silently -- the template carries the project's class version (Java
  25) against the version-61 "Java 17+" baseline.
- Shipped: the output is more than one file, and a class copied alone loses its bridge.
- Invariants javac cannot check: no nested classes/records under a SINGLE-blob injection (lambdas are
  fine), no imports of other rontolisp classes, written against the compiled value representation and
  kept in sync with its interpreter twin by hand.
- Embedded only: `defineClass`'s same-package requirement; lazy invokestatic resolution ordering
  (the init must run before the first bridge methodref); the base64 blob bloats the constant pool.
- Reflection back-calls need `setAccessible`; the template `.class` must be in
  `resource-config.json` (the native CLI reads it to compile).

## Class-closure injection (`--gpu`/`am.ik.gpu` [[gpu]], `objc:` [[objc]])

The single-blob limits are the injection's, not the mechanism's: one blob and one `defineClass` PER
class file in any order (siblings resolve lazily); ONE prefix rename over every file (`am/ik/gpu/` ->
`RontoLispGpu`) carrying nested classes without naming them, so the glue template is written against
the real library and type-checked by javac; a `getResourceAsStream` resource cannot follow and
travels as its own string constant through a public entry point. ~78 KB base64 for `am.ik.gpu`
(`JvmSimdVectorTemplate` is 83 KB). `--blas`'s flat template ([[linalg-blas]]) predates this.

- **Required guard: a test pinning the embedded class LIST against the package's actual class files**
  -- nothing can enumerate a package from a classpath, still less in a native image.
- **Definition order is not free**: the verifier loads a `catch` type WHILE defining the referencing
  class, so `ObjcException` goes first; an alphabetical list dies with `NoClassDefFoundError`.
- **A blob may call back into the program** through `bind(Class)`, which forces the eval runtime and
  roots `_apply` for the shaker.
- An enum `switch` in a template lowers to a synthetic `$1` class the blob does not carry -- use an
  if-chain; a test pins the absence of `$` siblings.
