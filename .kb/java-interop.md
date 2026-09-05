# `java:` interop (interpreter + JVM-compiler Java reflection bridge)

Package `java` (`LispNames.JAVA_PKG`, `PackageRegistry`; does NOT use `cl`): `java:new`,
`java:call`, `java:static`, `java:field`, `java:proxy`.

- Interpreter: `eval/JavaInterop`, `LispEvaluator.registerJava()`; value = `LispJavaObject`,
  prints `#<java <class>>`.
- JVM: `codegen.jvm.JavaBridgeTemplate` re-implements it against the compiled representation
  (raw ref; `"t"` = true; header-slot ArrayList = vector) — **KEEP THE TWO IN SYNC**.
  `JvmJavaRuntimeBuilder` renames to `RontoLispJavaBridge`, base64-embeds, `Lookup.defineClass`
  from `_javaInit`; call sites `JvmJavaInteropCompiler`. Needs JRE >= build JRE.
- Native image: template `.class` in `resource-config.json` — COMPILE works, INTERPRET does not.
- WASM: rejected; no `BuiltinFunctionWrappers` entry, so `#'java:call` is a compile error while
  the interpreter allows it.
- Trap: the template must have NO nested classes/records and NO rontolisp imports.
  `usesJava` forces `usesEval` and threads `JvmRuntimeBuilder.JavaPrint` into the print builders.
- `select()` = lowest total cost `COST_EXACT` < `COST_WIDEN` < `COST_CONVERT` < `COST_NARROW` <
  `COST_BOXED` < `COST_PROXY` (`COST_VARARGS` via `tryVarargs`), ties by stable signature string.
  `marshal`/`marshalSequence`/`accessibleMethod`. Symbols, hash tables, dotted lists and rank-2+
  arrays are NOT marshalled.

## Tests / docs
`JavaInteropTest` + `JvmJavaInteropCompilerTest` mirror the same cases — keep in step, headless
only. `examples/jvm/{java-interop,swing,life-gui}.lisp`; `doc/{en,ja}/guides/java-interop.md` +
five `reference/functions/java-*.md` (a GUI form hangs `DocExamplesTest`).
