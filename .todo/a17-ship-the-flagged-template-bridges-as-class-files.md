# Ship the --simd/--blas/--gpu, objc: and ffi: bridges as class files

Difficulty: Medium

The java: and geom: bridges now travel beside the program as `<Program>$JavaBridge` /
`<Program>$GeomBridge` class files (`JvmLispCompiler.bridgeClassFiles`, joined into
`runtimeClassFiles()`); their init only binds or `ldc`s. Five builders still
base64-embed and `Lookup.defineClass` at first use, so a `-o prog.jar` using them
dies under native-image with `UnsupportedFeatureError: Classes cannot be defined at
runtime` (measured 2026-09-26, GraalVM 25.0.4, for java: and geom; the other five
share the same emitted sequence):

- `JvmFfiRuntimeBuilder` -- same shape as java: (`bind(Class)`, per-program state):
  mechanical.
- `JvmBlasRuntimeBuilder` -- flat template, no bind.
- `JvmSimdRuntimeBuilder` -- its degrade relies on `defineClass` failing when
  `jdk.incubator.vector` is absent. A class loaded from disk links lazily, so an `ldc`
  alone may succeed and the failure move to the first kernel call: force linking
  inside the catch (`Lookup.ensureInitialized`, or a probe method) and keep
  `JvmSimdModuleFallbackTest` green.
- `JvmGpuRuntimeBuilder`, `JvmObjcRuntimeBuilder` -- class closures (one blob per class,
  definition order matters for objc). Shipped as files the order question disappears;
  the travelling class lists and their guard tests stay. objc is verifiable on macOS only.

Per bridge: name it after the program if it holds static per-program state
(bind), E2E it in `ShippedBridgeNativeImageE2eTest` where native-image can run it
(FFM downcalls need foreign config; check what the agent records), and update
`.kb/template-class-embedding.md`.

Also: the browser playground's `compileJvm` returns only the main class and drops
`runtimeClassFiles()` altogether (hash tables, complex, now the java:/geom bridges).
