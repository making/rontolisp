# `java:` interop (interpreter + JVM-compiler Java reflection bridge)

Package `java` (`LispNames.JAVA_PKG`, `PackageRegistry`; does NOT use `cl`): `java:new`,
`java:call`, `java:static`, `java:field`, `java:proxy`; the type specifier `java:object` and the
variable `java:*warn-on-reflection*` (static resolution, below).

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
  `COST_BOXED` < `COST_PROXY` (`COST_VARARGS` via `varargsCost`), ties by stable signature string,
  then (one parameter list, covariant variants) the most specific return type -- never the
  bridge that erases it. `marshal`/`marshalSequence`/`accessibleMethod`. Symbols, hash tables,
  dotted lists and rank-2+ arrays are NOT marshalled.
- THE rule lives ONCE for the interpreter and the compiler: `compiler/JavaOverloads` (`select`,
  `kindCost`, the tags). The interpreter (`eval/JavaInterop`) selects through it at run time over
  `compiler/ReflectiveJavaClasses`; `JavaBridgeTemplate` keeps a hand copy (it must stand alone),
  pinned by `JavaBridgeTemplateParityTest` -- change the two together.

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
  of (class, name), parsed member designators, overload per (class, member designator -- a
  tagged one is its own key --, kinds). `ConcurrentHashMap` only (the
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

## One resolution model: sites resolved before they run (Clojure's, interpreter included)
- `compiler/JavaSiteResolver.resolve(site)` = a PURE function of the site form + a lookup:
  RESOLVED (static class + fully tagged designator, e.g. `java.lang.Math` `max(int,int)`) or
  unresolved with a reason. Static types (`typeOf`, `JavaStaticType`): literal kinds; `java:new
  "C"` = exactly C (boxes/String = their Lisp kinds); a resolved member's declared type
  (`ofDeclared`: primitives/boxes/String -> kinds incl. nil for references, a final class ->
  {C, nil}, Object/Number/CharSequence/arrays... -> UNKNOWN, else `Bounded(C)`); `(the
  (java:object "C") x)`. An argument resolves the site only when EVERY kind it can have selects
  the same (executable, packed) (`COMBINATION_LIMIT` 256) -- so a resolved argument never changes
  the member (a String answer may be nil -> `append(boolean)` -> unresolved).
- THE semantic difference (documented in the guide): a receiver typed by an upper bound resolves
  among the bound's methods; an overload only the run-time class adds is not a candidate
  (`Collection.remove(Object)` vs `ArrayList.remove(int)`). Unresolved sites keep the run-time
  class + run-time kinds (Clojure's reflective fallback; no "static class, dynamic kinds" middle
  state -- a15's decision tree would introduce one).
- A resolved site runs as the explicit request on BOTH backends: interpreter
  `JavaInterop.callInstanceAs`/`fieldAs`/`newInstance`/`callStatic` with (static class,
  designator); JVM `JvmJavaInteropCompiler` emits `javaCallAs`/`javaFieldAs`/`javaNew`/`javaStatic`
  with the same strings. The receiver must be an instance of the static class (a declared type is
  trusted; a false one = the same deterministic error text on both). The tag makes the member
  exact; packing is re-derived from the run-time kinds, which a true declaration keeps inside the
  static set.
- Variable types: `compiler/JavaDeclarations` rewrites references to a typed variable in java:
  receiver/argument positions into `(the <spec> v)` -- the ONE scope walk (special forms
  structurally; built-in and user macros expanded, once per walk and cached, only to learn what
  they bind; never replaces a macro form: sites found in expansions are rebuilt in the original
  tree by identity, with `SourceProvenance.inherit` on every rebuilt cell; `macrolet`/unknown
  built-ins drop the scope). Three sources:
  - `(declare (type (java:object "C") v))`, body-head, bound or free; wins over inference.
  - let-initializer inference (Clojure's locals): a `let`/`let*` variable takes `typeOf` of its
    initializer (after user-macro expansion, the expansion walked first so its sites are lowered;
    a typed variable's spec for a bare symbol) unless it is special or ASSIGNED in its scope.
    The assignment scan (`assignedIn`) is conservative: `setq`/`psetq`/`multiple-value-setq`
    targets after full macro expansion, rebindings ignored, a `defvar` of the name counts, and a
    form it cannot see into (a special operator with no expansion here: `psetf`, `shiftf`, ...;
    an expansion that throws) assigns every candidate it mentions.
  - `(declaim (type (java:object "C") v))` / `(proclaim '(type ...))` (quoted literal only): for
    every later site in PROGRAM ORDER where v is not rebound; a later type proclamation of v with
    any other type ends it (a top-level one is read even when it mentions no java:). A defvar's
    init types nothing (any form may setq it).
  - Spelling (`JavaSiteResolver.specOf` / `typeOfSpec`): Bounded(C) = `(java:object "C")`; {C}
    exact = `(java:object "C" :exact)` (= `ofConstructed`, never nil; users may write it); a Lisp
    kind set = the smallest declared type covering it ("int" {integer}, "java.lang.Long"
    {integer,nil}, "boolean" {t,nil}, "java.lang.String" {string,string-1,nil}, a final class
    {C,nil}, "void" {nil}); FUNCTION/supplementary char: no spelling, not inferred. Wider = fewer
    sites resolve, never a different member.
  - One `JavaDeclarations` per program, fed EVERY top-level form in order (`lower`), a top-level
    `progn`/`eval-when` element by element (as the compile path's flattened program): it keeps the
    proclaimed types and its own special set (`SpecialVarCollector.collectDeclared`: defvar family,
    declaim/proclaim special, local `(declare (special ...))`). Interpreter: `LispEvaluator.
    javaDeclarations()` from `prepareJavaSites` on each top-level form; JVM: right after
    `PackageResolver` in `JvmLispCompiler.compile` (skipped, lookup unopened, when no form mentions
    java:). Both see the same proclamations at every site by construction.
  - Known gaps (both paths agree unless noted): a site that a user macro's expansion BUILDS is
    lowered on the compile path (user macros pre-expanded) but not by the interpreter (the
    evaluator re-expands; the rewrite has nowhere to live) -- visible only for an upper-bound
    receiver; a name made special by a form AFTER the binding (rontolisp's pessimistic
    program-wide special reading) is still inferred on both.
- Measured 2026-09-26 (`--warn-java-reflection`, compile path), before -> after let inference +
  declaim: swing.lisp 13 -> 32 of 53 sites resolved (the 21 left: defun parameters, user-function
  results, gethash values, a `let` assigned by `setq`); java-interop.lisp 8 of 17 unchanged (its
  receivers are defvar globals), 12 of 17 with a declaim per global (the 5 left: a global as an
  ARGUMENT is only an upper bound, and a `java:proxy` argument).
- Lookups: interpreter = `ReflectiveJavaClasses` (Class.forName without init; canonical Type per
  Class via ClassValue). JVM compile = `codegen.jvm.JvmClassFileLookup` over `am.ik.jvm.JvmClassPath`
  (`ClassFileInfo` reader): a JDK's `lib/ct.sym` for one release (java.home, else JAVA_HOME, else
  `java` on PATH; works in the native CLI, no reflection) + `--java-classpath` dirs/jars. It
  re-implements `Class.getMethods()` (the `PublicMethods` merge; interface statics not inherited;
  an interface has no superclass), `getMethod` (most specific return), `getField` (declared,
  interfaces, superclass) and the interpreter's `accessibleMethod`. Accessible = class-path class
  (unnamed module: `trySetAccessible` is true there) or public + package exported to all (the
  release's `module-info` Module attribute). `JvmClassFileLookupTest` pins identical candidates,
  fields, subtyping and site resolutions over a JDK corpus and a class-path root.
- ct.sym measured 2026-09-26 (GraalVM 25.0.4): 11.4 MB, 21707 entries, releases 8..25 (`P`) --
  the JDK's OWN release is in it, so one provider covers the default. Package-private supertypes of
  API classes are present (`AbstractStringBuilder`); impl classes are not (`ImmutableCollections$ListN`).
- Defaults: `--java-release` = the newest release in ct.sym (= the JDK's own = what the interpreter
  on it resolves against). No JDK found: class path only + one compile warning; JDK classes then
  resolve at run time. Output of a java: program depends on the release read (an input, like the
  class path); a program without java: is byte-identical. a14 must reconcile its class-61 default
  with this metadata default (a member chosen against 25 may not exist on 17).
- Candidate sets are `getMethods()` by name, static AND instance for both `java:call` and
  `java:static` (today's behavior kept; a14 must treat an instance member chosen for java:static).
- Warnings: `java:*warn-on-reflection*` (special, nil; `--warn-java-reflection` sets it) --
  interpreter: at top-level load for the sites the form shows (`sitesIn`), prefixed with the
  top-level form's `file:line`, and at the first resolution of a site not shown there; compile
  path: `JvmJavaSites.report` in source order, on from the flag or after a top-level `(setq
  java:*warn-on-reflection* t)`, with `SourceProvenance.prefix`. The interpreter memo is
  `LispEvaluator.javaSites` (identity, `EXPANSION_MEMO_LIMIT`).

## Tests / docs
`JavaSiteResolverTest`, `JavaDeclarationsTest` (compiler), `JvmClassFileLookupTest`,
`JavaBridgeTemplateParityTest`, `am.ik.jvm.JvmClassPathTest`.
`JavaInteropTest` + `JvmJavaInteropCompilerTest` mirror the same cases — keep in step, headless
only. `examples/jvm/{java-interop,swing,life-gui}.lisp`; `doc/{en,ja}/guides/java-interop.md` +
five `reference/functions/java-*.md` (a GUI form hangs `DocExamplesTest`).
