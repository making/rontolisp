# `java:` interop (interpreter + JVM-compiler Java reflection bridge)

Built-in package `java` (`LispNames.JAVA_PKG`, in `PackageRegistry`; does NOT use `cl`): `java:new`, `java:call`, `java:static`, `java:field`, `java:proxy`.

## Backends
- Interpreter: `eval/JavaInterop.java`, wired in `LispEvaluator.registerJava()`. Wrapped object = `LispVal` permittee `LispJavaObject(Object ref)`, prints `#<java <class>>`.
- JVM: `codegen.jvm.JavaBridgeTemplate` is a self-contained rewrite of `JavaInterop` against the compiled representation (wrapped object = raw reference; `"t"` = true; an ArrayList whose slot 0 is the `{dims, fillPointer, adjustable}` header = vector) — **KEEP THE TWO IN SYNC**. `JvmJavaRuntimeBuilder` renames it into the program's package as `RontoLispJavaBridge` (`renameClass`), base64-embeds it, and `Lookup.defineClass`es at first use from `_javaInit` (guard `_javaInited`; call sites `JvmJavaInteropCompiler`). Output stays one `.class` but needs a JRE >= the build JRE.
- Native image: the template `.class` is in `resource-config.json`, so the binary can COMPILE `java:` programs; INTERPRETING still fails there (no reflection metadata).
- WASM: `Cannot compile: java:...`. No `BuiltinFunctionWrappers` entry, so `#'java:call` is a compile error (the interpreter allows it).

## Traps
- The template must have NO nested classes/records (lambdas fine) and NO rontolisp imports; `Lookup.defineClass(byte[])` demands the same package.
- `usesJava` forces `usesEval` (proxied callables call back through `_apply` via `bind(Class)`), and threads `JvmRuntimeBuilder.JavaPrint` into the lisp-to-string builders. BigInteger, and HashMap when hash tables are used, still fall through to `toString`.

## Overload resolution and marshalling
- `select()` takes the lowest total cost `COST_EXACT` < `COST_WIDEN` < `COST_CONVERT` < `COST_NARROW` < `COST_BOXED` < `COST_PROXY`, ties by a stable signature string, never `getMethods()` order. One `marshal()` serves both selection and conversion.
- `java:proxy` adapts a Lisp callable to an interface (dispatch `(callable "method" arg...)`); a callable in an interface position is auto-proxied.
- Lists / rank-1 vectors -> `T[]` (`marshalSequence`) or a `java.util.List`; varargs tried as-is and packed (`tryVarargs`, flat `COST_VARARGS`, `*` signature suffix for the tie-break); Java arrays unmarshal to Lisp lists recursively, a returned `java.util.List` stays a `LispJavaObject`; a JDK-internal declaring class is re-resolved (`accessibleMethod`). Symbols, hash tables, dotted lists and rank-2+ arrays are NOT marshalled.

## Tests / docs
`JavaInteropTest` and `JvmJavaInteropCompilerTest` mirror the same cases — keep in step; headless JDK classes only. Examples `examples/jvm/java-interop.lisp`, `examples/jvm/swing.lisp` (`defpackage swing`, `swing:grid-window`/`swing:paint`), `examples/jvm/life-gui.lisp`. Docs `doc/{en,ja}/guides/java-interop.md` + five `reference/functions/java-*.md` — examples must be headless pure-compute, a GUI form hangs `DocExamplesTest`.
