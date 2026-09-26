# `java:` interop (interpreter + JVM-compiler Java reflection bridge)

Package `java` (`LispNames.JAVA_PKG`, `PackageRegistry`; does NOT use `cl`): `java:new`,
`java:call`, `java:static`, `java:field`, `java:proxy`.

- Interpreter: `eval/JavaInterop`, `LispEvaluator.registerJava()`; value = `LispJavaObject`,
  prints `#<java <class>>`.
- JVM: `codegen.jvm.JavaBridgeTemplate` re-implements it against the compiled representation
  (raw ref; `"t"` = true; header-slot ArrayList = vector) — **KEEP THE TWO IN SYNC**.
  `JvmJavaRuntimeBuilder` renames it to `<Program>$JavaBridge` and SHIPS it beside the class
  (`runtimeClassFiles()`); `_javaInit` only calls `bind(Class)`. Per-program name: `bind` stores
  that program's `_apply` statically. Call sites `JvmJavaInteropCompiler`. Needs JRE >= build JRE.
- Native image: template `.class` in `resource-config.json` — COMPILE works, INTERPRET does not.
- A compiled `-o prog.jar` native-images with agent config (`ShippedBridgeNativeImageE2eTest`,
  opt-in `-Drontolisp.native-image.e2e=true`). Measured 2026-09-26, GraalVM 25.0.4: the config
  covers only traced overloads -- an untraced `Math.max(double,double)` answers
  `MissingReflectionRegistrationError` (user doc: `guides/java-interop.md`, "Native image").
- WASM: rejected; no `BuiltinFunctionWrappers` entry, so `#'java:call` is a compile error while
  the interpreter allows it.
- Trap: the template must have NO nested classes/records and NO rontolisp imports.
  `usesJava` forces `usesEval` and threads `JvmRuntimeBuilder.JavaPrint` into the print builders.
- `select()` = lowest total cost `COST_EXACT` < `COST_WIDEN` < `COST_CONVERT` < `COST_NARROW` <
  `COST_BOXED` < `COST_PROXY` (`COST_VARARGS` via `varargsCost`), ties by stable signature string.
  `marshal`/`marshalSequence`/`accessibleMethod`. Symbols, hash tables, dotted lists and rank-2+
  arrays are NOT marshalled.

## Resolution: kinds, pure select, caches (both bridges, identical)
Per call the uncached bridge paid `getMethods()` (~2.5 us), `select()` (250 ns - 1.4 us),
`Class.forName` (~500 ns); a resolved `Method.invoke` is ~46 ns.
- A KIND is the smallest token every conversion cost is a pure function of: nil, t, integer,
  float, string of UTF-16 length 1 (may narrow to `char`), other string, BMP char,
  supplementary char, function value, host object = its exact `Class`. Canonical (constants /
  `Class`), compared by identity. Conses and Lisp arrays have NO kind (the cost sums the
  elements); values `marshal` never bridges (symbols, bignums, ...) have none either.
- `kindCost(kind, target)` is THE cost table; `marshal` = `kindCost` + `convert` for a value
  with a kind, element-wise `marshal` for a sequence. So cost and conversion cannot drift.
- `select(candidates, argc, cost)` returns an overload (executable, parameter types,
  packed-varargs flag) and reads no argument value: over `kindCost` of kinds it is a pure
  function of candidates x kinds (the shape a compile-time resolver can call). A call with a
  kindless argument passes `marshal` as the cost instead and is never remembered.
  `marshalArguments` then converts the values for the chosen overload only. The tie-break
  signature is built only on a cost tie.
- Caches: class by name, constructors of a class, accessible methods of (class, name), field
  of (class, name), overload per (class, member, kinds). `ConcurrentHashMap` only (the
  template cannot subclass `ClassValue`); a map is cleared at 4096 entries, a member keeps at
  most 16 memos (copy-on-write; a lost race only re-resolves). The template renders mutable
  character vectors once per call (`renderedAll`) before classifying.
- A new `marshal` rule is a new kind or a `kindCost` arm; the `remembered*` tests catch a
  kind that conflates two cost rows (integer/float).
- Measured 2026-09-26, JDK 25, 1M-iteration loop after warm-up, ns/call, before -> after
  (shared host, best of 2-3 runs):
  JVM output: Math.max 3438 -> 248, Math.abs 3112 -> 301, StringBuilder.length 2026 -> 148,
  append(int) 3404 -> 194, ArrayList.size 1231 -> 120, Integer.MAX_VALUE 374 -> 120,
  new StringBuilder() 577 -> 129. Interpreter: Math.max 4259 -> 1141, Math.abs 3791 -> 799,
  length 2931 -> 784, append 4664 -> 635, size 1520 -> 606, field 946 -> 593, new 1202 -> 591;
  the interpreter is now at its own loop floor (`(setq *x* i)` in the same loop: ~700-1000).
  What remains on the JVM is CHM gets, `String.hashCode` of the per-call name substrings,
  `Object[]` packing and `Method.invoke`.
- No `bench-report/` program: that suite compares portable ANSI CL across SBCL/ECL/ABCL and
  the wasm backend, none of which has `java:`.

## Tests / docs
`JavaInteropTest` + `JvmJavaInteropCompilerTest` mirror the same cases — keep in step, headless
only. `examples/jvm/{java-interop,swing,life-gui}.lisp`; `doc/{en,ja}/guides/java-interop.md` +
five `reference/functions/java-*.md` (a GUI form hangs `DocExamplesTest`).
