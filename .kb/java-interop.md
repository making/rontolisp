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

## Resolution caches (both bridges, identical)
Per call the uncached bridge paid `getMethods()` (~2.5 us), `select()` (250 ns - 1.4 us),
`Class.forName` (~500 ns); a resolved `Method.invoke` is ~46 ns. Both bridges now remember:
class by name, constructors of a class, accessible methods of (class, name), field of (class,
name), and the CHOICE per (class, member, argument kinds) -> executable + parameter types +
packed-varargs flag. `ConcurrentHashMap` only (the template cannot subclass `ClassValue`);
each map is cleared when it reaches 4096 entries, a member keeps at most 16 choices.
- A KIND is the smallest token of which every `marshal()` cost is a pure function: nil, t,
  integer, float, string of UTF-16 length 1 (may narrow to `char`), other string, BMP char,
  supplementary char, function value, host object = its exact `Class`. Kinds are canonical
  (constants / `Class`), compared by identity. Conses and Lisp arrays have NO kind (the cost
  sums the elements): such a call is resolved every time, never remembered. Values `marshal`
  never bridges (symbols, bignums, ...) have no kind either. The template renders mutable
  character vectors ONCE per call (`renderedAll`) before classifying.
- A hit re-marshals through the unchanged `tryFixedArity`/`tryVarargs` against the chosen
  parameter types, so values, errors and proxies are exactly as before; a hit that fails to
  marshal (a kind bug) falls back to `select()`. A new kind rule = a new `marshal` arm: keep
  `kindOf` in step or the memo returns a stale overload (the `remembered*` tests catch the
  integer/float conflation).
- The tie-break signature is built only on a cost tie.
- Measured 2026-09-26, JDK 25, 1M-iteration loop after warm-up, ns/call, before -> after
  (host under load; best of two):
  JVM output: Math.max 3438 -> 217, Math.abs 3112 -> 324, StringBuilder.length 2026 -> 117,
  append(int) 3404 -> 158, ArrayList.size 1231 -> 97, Integer.MAX_VALUE 374 -> 108,
  new StringBuilder() 577 -> 153. Interpreter: Math.max 4259 -> 1136, Math.abs 3791 -> 927,
  length 2931 -> 765, append 4664 -> 701, size 1520 -> 653, field 946 -> 616, new 1202 -> 683;
  the interpreter is now at its own loop floor (`(setq *x* i)` in the same loop: ~700-1000).
  What remains on the JVM (CHM gets, `String.hashCode` of the per-call name substrings,
  `Object[]` packing, `Method.invoke`) is what an invokedynamic call site would remove.
- No `bench-report/` program: that suite compares portable ANSI CL across SBCL/ECL/ABCL and
  the wasm backend, none of which has `java:`.
- Rejected: compile-time static binding. Selection depends on the receiver's run-time class
  and argument values; the compile classpath is the CLI's (not the user's, not reflectable in
  the native binary), so the output would depend on the build environment.

## Tests / docs
`JavaInteropTest` + `JvmJavaInteropCompilerTest` mirror the same cases — keep in step, headless
only. `examples/jvm/{java-interop,swing,life-gui}.lisp`; `doc/{en,ja}/guides/java-interop.md` +
five `reference/functions/java-*.md` (a GUI form hangs `DocExamplesTest`).
