# The small JVM runtime builders and `JvmLispCompiler`'s own code move onto `MethodCode`

Difficulty: Medium

## The problem

What remains of raw `List<Integer>` code outside a85-a87 (2026-09-29 site counts): the bridge and
feature runtimes -- `JvmDynVarRuntimeBuilder` 55, `JvmFlushStreamsBuilder` 40 (6 patches),
`JvmGpuRuntimeBuilder` 25, `JvmSimdRuntimeBuilder` 24, `JvmSecureRandomRuntimeBuilder` 22,
`JvmQuotePool` 21, `JvmFetchRuntimeBuilder` 20, `JvmHttpHandlerRuntimeBuilder` 17,
`JvmGeomRuntimeBuilder` 16, `JvmUnsupplied` 11, `JvmFfiRuntimeBuilder`, `JvmJavaRuntimeBuilder`,
`JvmObjcRuntimeBuilder`, `JvmMutexRuntimeBuilder` 10 each, `JvmMvChannel` 30,
`JvmUncaughtHandler` 27, `JvmExportRuntimeBuilder`'s raw half (123) -- and `JvmLispCompiler`
itself (65 raw sites: `<clinit>`, the probes, `_funName`, the TLS trust stubs), which hands the
definition its bodies as code lists plus declared max_stack/max_locals the writer ignores.

## What is needed

The same conversion (`.todo/a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode.md`), then the
`ClassDefinition.Builder.addMethod` overload that takes a max_stack and a max_locals has no caller
and goes. Byte-for-byte comparison before and after, over programs that reach each runtime
(`--simd`/`--blas`/`--gpu`, `java:`, `ffi:`, served handlers, `jvm-export`).
