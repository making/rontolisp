# `java:` interop (interpreter, JVM direct calls, generated interface classes, the reflection bridge)

Package `java` (`LispNames.JAVA_PKG`, `PackageRegistry`; does NOT use `cl`): `java:new`,
`java:call`, `java:static`, `java:field`, `java:proxy`, `java:subclass`, `java:reify`; the type specifier
`java:object` and the variable `java:*warn-on-reflection*` (static resolution, below).

- Interpreter: `eval/JavaInterop`, `LispEvaluator.registerJava()`; value = `LispJavaObject`,
  prints `#<java <class>>`.
- JVM: a RESOLVED site is a direct call (`JvmJavaDirectSites`, "Direct calls" below); a resolved
  `java:reify` / `java:proxy` and a function passed where an interface is expected an object of a
  generated class ("Implementing interfaces" below); every other site goes through
  `codegen.jvm.JavaBridgeTemplate`, which re-implements the run-time half
  against the compiled representation (raw ref; `"t"` = true; header-slot ArrayList = vector) —
  **KEEP THE TWO IN SYNC**. `JvmJavaRuntimeBuilder` renames it to `<Program>$JavaBridge` and SHIPS
  it beside the class (`runtimeClassFiles()`); `_javaInit` only calls `bind(Class)`. Per-program
  name: `bind` stores that program's `_apply` statically. Call sites `JvmJavaInteropCompiler`. The
  bridge is emitted only when a site needs it; it needs JRE >= build JRE.
- Native image: template `.class` in `resource-config.json` — COMPILE works, INTERPRET does not.
- A compiled `-o prog.jar` whose sites all resolve native-images with NO config (`--java-static`,
  "Direct calls" below); one that still needs the bridge native-images with agent config
  (`ShippedBridgeNativeImageE2eTest`, opt-in `-Drontolisp.native-image.e2e=true`). Measured
  2026-09-26, GraalVM 25.0.4: the config covers only traced overloads -- an untraced
  `Math.max(double,double)` at a site left to run time answers `MissingReflectionRegistrationError`
  (user doc: `guides/java-interop.md`, "Native image").
- WASM: rejected; no `BuiltinFunctionWrappers` entry, so `#'java:call` is a compile error while
  the interpreter allows it.
- A host `ArrayList` / `LinkedHashMap` a Java call answers is not a Lisp array / hash table
  ("What a host object is" below): the compiled printer and predicates ask the shared tests,
  the accessors refuse it through guards built on them.
- Trap: the template must have NO nested classes/records and NO rontolisp imports. The bridge
  forces `usesEval`, a generated interface class only the apply tier (`JvmJavaSites.needsApply`);
  `usesJava` threads `JvmRuntimeBuilder.JavaPrint` into the print builders.
- `select()` = lowest total cost `COST_EXACT` < `COST_WIDEN` < `COST_CONVERT` < `COST_NARROW` <
  `COST_BOXED` < `COST_PROXY` (`COST_VARARGS` via `varargsCost`), ties by stable signature string,
  then (one parameter list, covariant variants) the most specific return type -- never the
  bridge that erases it. `marshal`/`marshalSequence`/`marshalTable`/`accessibleMethod`. Symbols
  (but `|false|`), ratios, dotted lists and rank-2+ arrays are NOT marshalled ("Bignums and
  specialized vectors", "Java's false and hash tables" below for what is).
- THE rule lives ONCE for the interpreter and the compiler: `compiler/JavaOverloads` (`select`,
  `kindCost`, the tags). The interpreter (`eval/JavaInterop`) selects through it at run time over
  `compiler/ReflectiveJavaClasses`; `JavaBridgeTemplate` keeps a hand copy (it must stand alone),
  pinned by `JavaBridgeTemplateParityTest` -- change the two together.
- `accessibleMethod` (all three lookups: `ReflectiveJavaClasses`, the bridge template,
  `JvmClassFileLookup`) re-resolves a public method whose declaring class cannot be called
  through to the same method on an accessible supertype of the RECEIVER class, the walk
  starting at the receiver (Clojure's Reflector does the same). HashMap's entry iterator
  inherits `hasNext` from `HashMap$HashIterator`, which implements nothing; until 2026-10-04
  the walk started at the declaring class and a run-time site answered `No matching method`.

## What a host object is (one rule; interpreter = `LispJavaObject`)
- Compiled: `JavaBridgeTemplate.isJavaObject` = `JvmJavaDirectSites._jhost`, test for test
  (`JavaBridgeTemplateParityTest#theBridgeAndADirectSiteCountTheSameHostObjects`): NOT a
  `Long`/`Double`/`BigInteger`/`String`, NOT any Java array (characters, ratios, conses,
  function values, instances, streams, `#d`/`#f`/octet vectors -- a Java array a call answers
  is unmarshalled into a list, so none is ever a host), NOT an `ArrayList` whose slot 0 is an
  `Object[]` (a Lisp array), NOT a `LinkedHashMap` holding an `ArrayList` under
  `RontoHashTable.ORDER_KEY` (a hash table), NOT a class in `am.ik.rontolisp.runtime`
  (`RontoComplex`). The bridge spells the key and the package itself (no rontolisp imports);
  `theBridgeSpellsTheRepresentationAsTheRuntimeDoes` pins both.
- The array and table arms are ONE test each in a compiled program: `_jlarr` (non-empty
  `ArrayList`, slot 0 an `Object[]`) and `_jltab` (`LinkedHashMap`, an `ArrayList` under the
  order key), `JvmJavaDirectSites.lispArray()` / `lispTable()`, built on first use. `_jhost`
  calls them, and so -- in a `java:` program only (`Ctx.javaSites`, `JavaPrint`), a program
  without `java:` keeps its `instanceof` bytecode -- do the printer's array and hash-table
  arms (`JavaPrint.lispArray/lispTable`), `_hashP` (`hash-table-p`, the class dispatch, typep
  `hash-table`) and `%arrayp` (`arrayp`, `vectorp`, typep `vector`/`array`/`sequence`,
  `type-of`). `stringp` / `%simple-array-p` read the header themselves. Before, measured
  2026-09-26: `hash-table-p` of a host map `T`, printing it (in a program with hash tables)
  `NullPointerException`; `arrayp` of a host list `T`, `vectorp` / typep / `type-of`
  `IndexOutOfBoundsException` or `ClassCastException`
  (`JavaInteropPrograms.HOST_COLLECTION_PROGRAM`, both backends).
- The bridge's `kindOf` ends with it (a host's kind = its exact class, anything else none);
  `_jkind` answers HOST / NONE through `_jhost`; a direct site's exact-class kind test of
  `ArrayList` / `LinkedHashMap` / a runtime class also calls `_jhost` (`mayHoldALispValue`).
- Messages: the bridge's `describe` is the program's `_lispToString`, bound in `bind` beside
  `_apply` (a shaker root while the bridge travels; `REFLECTIVELY_FOUND_METHODS` keeps it in
  the class on a split), the old minimal text only when absent -- so a site left to run time
  shows `(1 2)`, `1.0e10`, `#(0 0)` as the interpreter and a direct site do.
- A Java `BigInteger` result is a Lisp integer on all three paths (a fixnum when
  `bitLength() < 64`: interpreter `unmarshal`, bridge `unmarshal`, `_junm`); `JavaStaticType.
  becomesLisp` counts BigInteger, its supertypes and its subclasses. A declared BigInteger (or
  subclass) is {integer, bignum, nil}, a constructed one {integer, bignum}; its supertypes stay
  UNKNOWN. Decided 2026-09-26: the compiled representation cannot hold a
  host `BigInteger` apart from a bignum, so the interpreter gave up calling its methods
  (`(java:call (java:new "java.math.BigInteger" "5") "add" ...)` refused `5` on both; since
  2026-10-03 the fixnum `5` is called as an `Integer`, which has no `add` -- "A Lisp value as a
  `java:call` receiver" below).
- Measured 2026-09-26 before the rule, `(defun f (x) (java:call x "size"))` compiled: a list /
  struct / function / stream / condition was described `#<java [Ljava.lang.Object;>`, a ratio
  `#<java [Ljava.math.BigInteger;>`, a bignum `#<java java.math.BigInteger>`, `1.0e10` as
  `1.0E10`; a vector / string / bit vector / fill-pointer vector answered `size()` (the
  `ArrayList` with its header), a hash table answered 2, a complex / `#d` vector `No matching
  method RontoComplex.size` / `[D.size`; `(java:new "java.math.BigInteger" "5")` printed `5`
  compiled and `#<java java.math.BigInteger>` interpreted. All agree now
  (`testsupport/JavaInteropPrograms.HOST_OBJECT_PROGRAM`).
- The ACCESSORS refuse a host collection as the interpreter does, in a `java:` program only
  (a program without `java:` emits nothing new): every hash-table accessor's call site runs
  the table through `_jcktab(v, "OP")` (`JvmHashTableCompiler.emitHostTableGuard`; after
  gethash's default / `%puthash`'s value, which the interpreter evaluates first) and every
  array accessor's through `_jckarr` (`JvmArrayCompiler.emitHostArrayGuard`: `aref`, `%aset`,
  `row-major-aref`, `array-dimensions`, `array-element-type`, the fill-pointer surface) --
  `JvmJavaDirectSites.tableGuard()` / `arrayGuard()`: an instance of the shared class the
  shared test rejects throws the interpreter's `HASH-TABLE` / `ARRAY` type-error, renamed by
  `_opTypeErr` after the operator the site hands in (its reported name; the fill-pointer
  surface and `array-element-type` are named since 2026-09-27). Only a host collection
  of the accessor's OWN class is refused here; any other wrong type is the every-program check
  in front of it ([error-handling.md](error-handling.md), "A sequence, array or hash-table
  operand of the wrong kind"). `_length`'s array arm asks `_jlarr`, so a
  host list is `LENGTH`'s `SEQUENCE` type-error, and so is every sequence function that
  measures first (`coerce`, `position`, `fill`, ...). `hash-table-test` and the rehash
  accessors, constants elsewhere, evaluate and guard the table first. Before, measured
  2026-09-26: `gethash` of a key the host map lacks answered `NIL`, `(setf gethash)` wrote a
  bucket INTO the host map and then threw a `NullPointerException`, `hash-table-test` answered
  `EQUAL`, `length` / `aref` / `hash-table-count` threw Java exceptions
  (`JavaInteropPrograms.HOST_ACCESSOR_PROGRAM`, both backends; its rows print the message
  too since 2026-09-27).

- `eq`/`eql` on a host object is identity, `equal`/`equalp` its `equals` (a Lisp value on
  the right handed as the receiver rule below converts it); on the JVM the equality helpers
  ask `_jhost` / `_jrecv` in a `java:` program only ([eq-numbers.md](eq-numbers.md), "Host
  objects").

## A Lisp value as a `java:call` receiver (one rule, three copies)
- A receiver that is no host object but a Lisp value of a receiver kind
  (`JavaOverloads.isReceiverKind`: every kind but NIL and FUNCTION) is called as what `convert`
  makes of it for an `Object` parameter: string `String` (a mutable one rendered first), fixnum
  the narrowest box (`Integer`, else `Long` -- the argument rule, so `(java:call 5 "equals" 5)`
  is `Integer.equals(Integer)`, T), float `Double`, bignum `BigInteger`, BMP char `Character`,
  supplementary char `Integer`, `t` `Boolean.TRUE`, `|false|` `Boolean.FALSE`. Anything else keeps `java:call expects a
  java object ..., got X`. `java:field` is unchanged (a string there is a class name).
- Copies: interpreter `LispJavaObject.receiverObject` (run-time `callInstance`,
  `invokeResolved` and `equal`), bridge `receiverObject`, direct sites and `_equal` `_jrecv` (`JvmJavaDirectSites.
  RECEIVER`, called only after `_jhost` refused, so a host receiver pays nothing new; the
  converted object replaces the receiver slot, the messages show the value handed in).
  `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteCallALispValueAsTheSharedRuleSays` pins
  the three against `JavaOverloads.receiverClassName`.
- Resolver: `JavaSiteResolver.callReceiverClass` -- a receiver whose non-nil kinds all name
  one class (`receiverClassName`; INTEGER names none, its box is the value's) resolves among
  that class's methods: `(java:call "abc" "codePointAt" 0)` is a direct call, also under
  `--java-static`. A resolved site whose class no receiver kind converts to answers `the
  receiver is not a C, got 5` (before: `expects a java object`).
- Decided 2026-10-03 (Clojure `(.codePointAt "abc" 0)` was refused): a string answered by Java
  was a Lisp string no further call could take. Supersedes, for receivers, the 2026-09-26
  BigInteger note above: a bignum is callable as a `BigInteger`; a host BigInteger fitting a
  fixnum is a fixnum, called as an `Integer`/`Long`. Pins: `JavaInteropPrograms.
  LISP_RECEIVER_PROGRAM` (both backends), `HOST_OBJECT_OUTPUT` (`No matching method
  java.lang.Double.size`), `JavaSiteResolverTest#aLispValueReceiverResolvesAsTheClassItConvertsTo`.

## Bignums and specialized vectors
- BIGNUM is a kind (`JavaKind.Lisp`, after INTEGER): `kindCost` = `BigInteger` EXACT, a supertype
  of it (`Number`, `Object`, `Comparable`, `Serializable`) BOXED, anything else NO_MATCH -- no
  `long`, and no lossy `double` (a user converts with `float`). INTEGER -> `BigInteger` is
  CONVERT (`BigInteger.valueOf`): lossless but after every primitive that holds it; a tie with
  `double` goes to `double` by the signature order. Kind tests: interpreter `LispBigInteger`,
  bridge / `_jkind` / `emitKindTest` `instanceof BigInteger`; conversion = the value itself.
  A literal bignum is a BIGNUM static type (a site resolves on it); spelled
  `(java:object "java.math.BigInteger")` = {integer, bignum, nil} (`KIND_SPELLINGS`).
- A rank-1 SPECIALIZED vector is a sequence like any vector, its elements what `aref` reads (a
  float of every width as a double, an unsigned integer widened): interpreter `LispFloatArray`
  (`dims()[0]`, `elementAt`) and `LispIntVector`; compiled `double[]`/`float[]` `{rank, dim, e...}`,
  bfloat16 `short[]` `{rank, hi, lo, e...}` read through the program's `_bf16Value`, `long[]`
  `{width, e...}`, octet `byte[]` `{8, e...}` -- another `byte[]` is a quantized matrix, another
  rank no sequence. Two copies: the bridge's `packedElements` (binds `_bf16Value` by name in
  `bind`, so it is a shaker root beside `_lispToString` and in `REFLECTIVELY_FOUND_METHODS`) and
  `_jseq`'s `emitPackedElements` arms, which `_jkind` reaches as KIND_ARRAY. The direct sites
  test only the shapes the program can hold (`JvmJavaDirectSites.packedVectors` from
  `usesFloatArray` / `usesIntArray`), so a program without packed arrays emits what it did.
- Measured 2026-09-26 before: every one of these matched no parameter on both backends
  (`No matching method`). Pins: `testsupport/JavaInteropPrograms.SPECIALIZED_AND_BIGNUM_PROGRAM`
  (dispatched, bridged and resolved sites, both backends),
  `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteReadASpecializedVectorAlike` and its
  bignum corpus rows, `JavaSiteResolverTest#bignumsAndBigIntegerParametersResolve`.

## Java's false and hash tables (e69, 2026-10-08)
- The symbol `|false|` (`LispNames.JAVA_FALSE`, Java's own spelling) is the kind
  `JavaKind.Lisp.FALSE`: t's twin in `kindCost` (`boolean` EXACT, `Boolean` WIDEN, a supertype
  BOXED), converting to `false` / `Boolean.FALSE` where nil is `null`; a receiver kind
  (`Boolean.FALSE`); a quoted `'|false|` types a site (`JavaSiteResolver.typeOf`). A callback
  answering it for a `boolean`/`Boolean` returns false through the same marshal. Clojure's
  false object IS that symbol (`ClojureLowering.FALSE_VALUE_NAME`), so its false crosses with no
  Clojure name in `java:`. Unmarshal is unchanged: host false is nil (the Clojure lowering's
  `booleanAnswer` sites aside). Copies: interpreter `kindOf`/`convert`/`LispJavaObject.
  receiverObject`, the bridge's `KIND_FALSE`/`JAVA_FALSE` (pinned to `LispNames` by
  `theBridgeSpellsTheRepresentationAsTheRuntimeDoes`), `_jkind` (a `"false".equals` after the
  `"T"` test, so only a symbol that is neither pays it) and `emitKindTest`/`emitConvert`.
- A hash table converts, for a parameter a `java.util.LinkedHashMap` is assignable to (`Map`,
  `HashMap`, `Object`, ...), to a fresh one of its live entries in insertion order, keys and
  values marshalled as `Object` (functions per the site's mode), `COST_BOXED` + their costs, as
  a sequence converts to a `List`; an `equalp` table's key is the one first stored. Copies:
  `JavaInterop.marshalTable`; the bridge's `tableEntries`/`marshalTable`, reading through the
  program's `_hashValues` bound in `bind` (a shaker root and `REFLECTIVELY_FOUND_METHODS` like
  `_bf16Value`); the direct sites' `_jtab` (flat `{k, v, ...}` over `_hashValues`), the
  `KIND_TABLE` code and the `_jcost$N` / open `_jconv$N` table arms, all only when
  `JvmJavaDirectSites.hashTables` named `_hashValues` (a program without the hash runtime holds
  no table). `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteReadAHashTableAlike`.
- Before, measured 2026-10-08 (both backends): `(java:call l "add" '|false|)` and a table where a
  `Map` is expected were `No matching method`, a callback answering `|false|` for a `boolean`
  `java:reify: cannot return |false| as boolean`. Pins: `JavaInteropPrograms.
  FALSE_AND_TABLE_PROGRAM` (`JavaInteropTest` / `JvmJavaInteropCompilerTest`: resolved,
  dispatched and bridged sites, receivers, callbacks, tombstoned and `equalp` tables, refusals),
  `HOST_OBJECT_OUTPUT` (a table's `Objects.toString` is `"{}"`),
  `JavaSiteResolverTest#aQuotedFalseSymbolResolvesAsJavasFalse`, the parity corpus.
- Known gap, not this rule's: a direct site's `_jlarr` (and the bridge's `isJavaObject`) asks a
  host `ArrayList` subclass its own `isEmpty`/`get`, so a `java:subclass` of `ArrayList` whose
  `isEmpty` answers false while empty throws `index out of bounds` on the JVM (todo e74).

## Resolution: kinds, pure select, caches (both bridges, identical)
Per call the uncached bridge paid `getMethods()` (~2.5 us), `select()` (250 ns - 1.4 us),
`Class.forName` (~500 ns); a resolved `Method.invoke` is ~46 ns.
- A KIND is the smallest token every conversion cost is a pure function of: nil, t, `|false|`,
  integer, bignum, float, string of UTF-16 length 1 (may narrow to `char`), other string, BMP
  char, supplementary char, function value, host object = its exact `Class`. Canonical
  (constants / `Class`), compared by identity. Conses, Lisp arrays and hash tables have NO kind
  (the cost sums the elements, a table's keys and values); values `marshal` never bridges
  (other symbols, ratios, ...) have none either.
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
  (`Collection.remove(Object)` vs `ArrayList.remove(int)`) -- whether the site resolves to one
  member or DISPATCHES (below). Only a site whose receiver class is unknown (or that the resolver
  cannot name: no candidates, nothing viable, an unlinkable parameter) keeps the run-time class +
  run-time kinds (Clojure's reflective fallback).
- A resolved site runs its RESOLVED MEMBER (or, dispatched, the overload its kinds select) on
  both backends -- interpreter
  `JavaInterop.invokeResolved` over the site's reflective executable, JVM a direct call -- with the
  same checks in the same order and the same texts: receiver not a java object (`java:call expects
  a java object as the first argument, got X` / `java:field expects a class-name string or a java
  object, got X`), not an instance of the static class (`java:call: the receiver is not a C, got X`
  / `java:field: the object is not a C, got X`), then each argument whose run-time kind is not one
  the resolution counted on (`JavaSite.Argument`): `java:<op>: argument N is not <expected>, got
  X`, expected = the declared class (`JavaSiteResolver.declaredClass`, the outermost `(the
  (java:object "C") ...)`) or the kinds spelled out (`an integer or nil`; a primitive declaration
  too). Only a false `the`/declaration can deliver such a value; it is never converted for a member
  it was not chosen for (a13 converted it when convertible and re-derived the varargs packing --
  both gone). X is `print` / the program's `_lispToString`, so the texts agree for every value.
  Then the member is called with the packing the resolution chose; what it throws is `error
  calling C.m: <throwable>` / `error constructing C: ...` / `error reading field f: ...`, a
  `java:java-exception` carrying the throwable ("What a member throws", below) -- unless a
  function called back from Java raised it ("What a callback raises", below).
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
    {C,nil}, "void" {nil}, "java.math.BigInteger" {integer,bignum,nil}); FUNCTION/supplementary
    char: no spelling, not inferred. Wider = fewer
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
    receiver; a name a form AFTER the binding makes special (the compile path's special set is
    program-wide) is still inferred on both.
- Measured 2026-09-26 (`--warn-java-reflection`, compile path), before -> after let inference +
  declaim: swing.lisp 13 -> 32 of 53 sites resolved (the 21 left: defun parameters, user-function
  results, gethash values, a `let` assigned by `setq`); java-interop.lisp 8 of 17 unchanged (its
  receivers are defvar globals), 12 of 17 with a declaim per global (the 5 left: a global as an
  ARGUMENT is only an upper bound, and a `java:proxy` argument).
- Lookups: interpreter = `ReflectiveJavaClasses` (Class.forName without init, through the
  program's Java class loader -- "The program's Java class path" below; canonical Type per
  Class via ClassValue, so two loaders' lookups agree on every class both see). JVM compile = `codegen.jvm.JvmClassFileLookup` over `am.ik.jvm.JvmClassPath`
  (`ClassFileInfo` reader, a `java.lang.classfile` `ClassModel`; a class newer than the running
  JDK is read with its version lowered in a copy): a JDK's `lib/ct.sym` for one release (java.home, else JAVA_HOME, else
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
  class path); a program without java: is byte-identical.
- Class version (decided with the default, 2026-09-26): a program with java: sites is stamped
  `max(61, 44 + R)`, R = the release its sites resolved against (`JvmLispCompiler.classMajorVersion`,
  every `$PartN` too) -- javac's `--release` model: a member chosen against 25 may not exist on
  17, and a JRE older than R refuses the class at load instead of throwing `NoSuchMethodError` at
  the first call. Default release 17 was rejected: it would resolve against a different API than
  the interpreter on the compiling JDK. The bridge's own floor (the build release) is separate and
  unchanged. A program without java: stays 61.
- Candidate sets are `getMethods()` by name: static AND instance for `java:call` (Java calls a
  static through an instance), STATIC ONLY for `java:static` (`JavaOverloads.staticMethods`, on all
  three: resolver, interpreter, bridge -- its memo key is prefixed so a call's and a static's
  choices of one name never mix). An instance method under `java:static` only ever failed (an NPE
  out of `Method.invoke`); now it is no candidate: `No matching method C.m with N argument(s)`.
- An INTERFACE receiver's `java:call` candidates add `Object`'s public instance methods it does
  not redeclare (`JavaSiteResolver.callableMethods`; JLS 9.2 members, final ones included --
  javac emits `invokeinterface List.getClass`, which links by JVMS 5.4.3.4). The lookups keep
  `getMethods()` semantics (none of them listed; `JvmClassFileLookupTest#anInterfaceReceiver...`
  pins both), so `publicMethods()` / reify slots are unaffected. Before 2026-09-26 such a site
  (a declared `List`, a `java:reify` object) was left to run time and `--java-static` refused it.
- `(java:field "C" "f")` reads a static field: an instance field so named is `java:field: field
  C.f is not static` on all three (was an NPE), and the resolver leaves it unresolved with that
  reason. `(java:field obj "CONSTANT")` of a static field still reads it.
- Warnings: `java:*warn-on-reflection*` (special, nil; `--warn-java-reflection` sets it) --
  interpreter: at top-level load for the sites the form shows (`sitesIn`), prefixed with the
  top-level form's `file:line`, and at the first resolution of a site not shown there; compile
  path: `JvmJavaSites.report` in source order, on from the flag or after a top-level `(setq
  java:*warn-on-reflection* t)`, with `SourceProvenance.prefix`. The interpreter memo is
  `LispEvaluator.javaSites` (identity, `EXPANSION_MEMO_LIMIT`).

## Dispatch: static class, argument kinds read when it runs
- `JavaSiteResolver.select`: one member when every kind combination agrees (as before);
  otherwise -- an argument UNKNOWN or `Bounded`, too many combinations, combinations that
  differ, or one that selects nothing -- a DISPATCHED site (`JavaSite.dispatched()`,
  `resolved()` is true, `executable` null, `designator` as written): `overloads` =
  `JavaOverloads.ranked(candidates, argc)` (each executable fixed-arity and/or packed, in the tie
  order `select` breaks by) minus the ones no value a closed argument can have matches
  (`viable`; exact: `select` ignores NO_MATCH overloads; a bounded argument only drops a
  non-boolean primitive). Nothing viable -> unresolved (old reason text when kinds were closed);
  any unlinkable parameter of a viable overload -> unresolved. Result = `ofDeclared` of the
  overloads' one return type, else UNKNOWN (NEW: constructed).
- `JavaSite.Argument`: `kinds` (closed, checked as before), or empty + `bound` (value is nil or
  a host object instance of it; `Bounded` types an argument now) or empty + no bound (anything,
  unchecked). Error text for a bound: `java:<op>: argument N is not a <C>, got X`.
- THE choice: `JavaOverloads.selectRanked(ranked, argc, cost)` = the first strictly cheapest,
  `overloadCost` = fixed sum or `COST_VARARGS` + sum (the `select` arithmetic). Equals `select`
  whenever `beats` at equal cost is a strict total order (signature string, then covariant
  return, then `tieKey`); a non-transitive trio of covariant variants of one parameter list
  could differ -- none found: `JavaSiteResolverTest#theRankedOrderChoosesWhatSelectChooses`
  checks >1000 (candidates, kinds) pairs over ten JDK classes. No-match text = the run-time one
  with the STATIC class: `No matching method C.<designator> with N argument(s)` /
  `No matching constructor for <designator> ...`.
- Interpreter: `JavaInterop.invokeResolved` -> `dispatch`: receiver check, `keepsPromise` per
  argument, `selectRanked` over `kindCost` (a kindless argument: `marshal` cost, never
  remembered), memo per site IDENTITY (`DISPATCHES`, `SiteKey`; same limits as `CHOICES`) --
  without it a 9-overload site (`String.valueOf`) cost ~1425 ns/call vs ~1165 for the old
  run-time path; with it both measure ~1200 (2026-09-26, noisy shared host).
- JVM (`JvmJavaDirectSites.SiteBuilder.emitDispatch`): the argument checks (`emitCheck`: the kind
  chain, or null / `_jkind == HOST` + `instanceof` bound under a NoClassDefFoundError handler ->
  `No such class: B`); one `_jcost$N(Object)I` call per distinct (argument, parameter type) into
  an int local; the overloads scanned in rank order (`IFLT` skips NO_MATCH, replace only when
  strictly cheaper); the no-match throw; one arm per overload (`iload best; iconst k;
  if_icmpne`, the last untested): `_jconv$N(Object)T` per argument (packed tail: `newarray` +
  store), `emitInvoke`, `emitUnmarshal`, `areturn`. Shared helpers, made once per attempt:
  - `_jkind(Object)I`: the bridge's `kindOf` order as codes: a Lisp kind's ordinal
    (`LISP_KINDS` = `JavaKind.Lisp.values()`), then cons, Lisp array (a specialized one too),
    host (`_jhost`), hash table (after the host test, only where `hashTables` named the
    program's `_hashValues`), none (any other symbol, ratio, ...).
  - `_jseq(Object)Object[]`: a cons's cars (null if dotted / function-terminated), a rank-1 Lisp
    array's elements (fill pointer; the PACKED long[] shape with MIN_VALUE -> nil), a rank-1
    specialized vector's ("Bignums and specialized vectors"), else null.
  - `_jcost$N` per (parameter type, function arm or not): `_strv` first (a built string;
    elements too), then the compile-time `kindCost` constant per code, a sequence
    `COST_CONVERT`/`COST_BOXED` + its elements' `_jcost` (array component / Object for an
    `ArrayList`-assignable type), a host object `instanceof` + `getClass() ==`. Without the
    function arm it is the RETURNED variant a16's callbacks test a value with
    (`returnedCost`: a function is never made a proxy on the way back).
  - `_jconv$N` per (parameter type, functions, sequences): the existing `emitConvert` arm per
    code, the host object `checkcast`; the OPEN variant (both; an argument that `mayBeFunction`:
    unknown, or FUNCTION in its kinds) adds the FUNCTION arm (the interface's generated proxy
    class) and the sequence arm (array via `newarray` + element `_jconv`, or an `ArrayList` of
    element `_jconv(Object)`); the closed one neither; the RETURNED one (`returnedConvert`)
    sequences without functions.
  - The method builders share a `Body` (assembler, locals, handlers, kind tests, conversions,
    unmarshal) that `SiteBuilder` extends.
- No dispatched site needs the bridge (since a16): an argument that may be a function where an
  overload's parameter is an interface, or an array (of arrays) of one, becomes the interface's
  generated proxy class. So `String.join("-", xs)` with unknown `xs` compiles under
  `--java-static`, generating the `CharSequence` and `Iterable` proxy classes a function would
  need (`JvmJavaSites.passesAFunction` predicts `_apply` from the same rule); a15 measured it
  needing the bridge.
- Measured 2026-09-26 (JDK 25, default output, `--warn-java-reflection` counts): sites left to
  run time swing.lisp 21 -> 8, life-gui.lisp 21 -> 8, java-interop.lisp 9 -> 9 (every one left
  has an unknown receiver). `(defun mx (x y) (java:static "java.lang.Math" "max" x y))` +
  one call: 88,258 bytes (51,173 class with the eval runtime + 37,085 bridge) -> 8,779 (one
  class). 1M calls after warm-up, ns/call: `mx` bridge 82-101 -> dispatch ~1 (inlined), a
  `String.valueOf` site 103-137 -> 82 (allocation-bound). Interpreter `mx` ~1750-2185 before,
  ~1710-2115 after. Native image: the `--java-static` E2E program with three dispatched sites
  (numbers, t, a list to `char[]`, a vector to `int[]`, a list to `Object[]`) builds with no
  metadata and prints what `java -jar` prints.

## Direct calls: resolved sites without reflection (JVM)
- A site resolves only through what bytecode can name (`JavaType.isLinkable` = primitive, or
  class-file `ACC_PUBLIC` (`isPublic`: a `protected` member class counts, as javac writes it) and
  accessible): the static class of every operator, each chosen parameter type (a varargs array's
  element type), and a host kind (`ofDeclared` / `ofConstructed` give UNKNOWN for anything else).
  `java:new` of an abstract class or interface is left to run time ("class C is abstract"). The
  rule is in the shared resolver, so the interpreter leaves the same sites to run time -- and
  every resolved site CAN be a direct call; there is no "resolved but bridged" state. Both lookups'
  `isPublic` / `isAbstract` / `isLinkable` are pinned by `JvmClassFileLookupTest#theTypesAgree`;
  `ReflectiveJavaClasses.isAccessible` reads the class-file flag too (it disagreed for a
  protected member class before).
- `JvmJavaDirectSites`: one `private static Object _jsite$N(Object...)` per site SHAPE (operator,
  class, designator, packing, argument kinds + declared names; past 200 values one `Object[]`).
  The site evaluates receiver and arguments left to right (`--gpu` materializes each; an argument
  counted as a string is `_strv`-rendered at the site) and calls it. Body: `_jhost` (the bridge's
  `isJavaObject`) + `instanceof C` for a receiver -- class resolution under a handler catching
  `NoClassDefFoundError` -> `No such class: C`, an `ldc C` for a class-named site; per argument a
  kind dispatch (the bridge's `kindOf` tests: `"T".equals`, `instanceof Long`, `int[]` of length 1
  and `isBmpCodePoint`, quote-framed `String` (length 3 = STRING_1), exact `getClass()` for a host
  kind -- `_jhost` for an `ArrayList`/`LinkedHashMap`/runtime class, a `BigInteger` never a
  host kind) into the bridge's `convert`/`convertLong` arm for (kind, parameter), a `checkcast` to
  a class parameter after the join (the frame pass may merge arms to `Number`/`Object`); then
  `invokevirtual`/`invokeinterface` (InterfaceMethodref on an interface owner)/`invokestatic`/`new`
  + `invokespecial`/`getfield`/`getstatic` with the static class as OWNER (javac's qualifying type:
  a method of a package-private superclass links through it) and the chosen descriptor (the most
  specific covariant return), under a handler catching Throwable -> the `error ...` text; the value
  converted back specialized to the declared type (`_junm` = the bridge's `unmarshal` for a type a
  box/String/array may hide behind, `_jarr` its `arrayToList` over every array type without
  `java.lang.reflect.Array`).
- Nothing in a direct call reflects: a FUNCTION kind for an interface parameter -- at a
  dispatched site in an open `_jconv$N` too -- is the interface's generated proxy class
  (`JvmJavaImplementations.proxyFactory`, "Implementing interfaces" below); until a16 it called
  `_javaInit` + the bridge's `javaProxy`, the one reflective arm, and `--java-static` refused
  such a site. The bridge is emitted when `JvmJavaSites.needsBridge` (a pre-scan: an unresolved
  site, an unresolved `java:reify` / `java:proxy`) or when forced: a site a pass rebuilt after
  the scan and that needs it calls the absent `_javaInit`, `gateGroupFor` maps it to
  `GROUP_JAVA_BRIDGE`, and the attempt retries with the bridge. `usesEval` / the `_apply` root follow
  the bridge, not `usesJava`; `JavaPrint` (`#<java C>`) follows `usesJava`.
- `--java-static` (`JvmLispCompiler.Builder.javaStatic`, `JvmSourceCompiler`, CLI; JVM outputs
  only): no bridge ever; each site `JvmJavaSites.bridgeReason` names is refused at codegen and the
  attempt fails listing all of them with positions. Native-image of the resulting jar needs no
  metadata: measured 2026-09-26 (GraalVM 25.0.4), a 17-site program (constructor, instance,
  interface, static, varargs, field, chain, declared receiver, regex split, `LocalDate`, a caught
  exception) -> 17,792-byte jar, `native-image --no-fallback -jar` in 30 s, 14.7 MB executable,
  output identical to `java -jar`
  (`ShippedBridgeNativeImageE2eTest#aJavaStaticJarRunsAsANativeImageWithNoConfiguration`, opt-in
  `-Drontolisp.native-image.e2e=true`; the same class keeps the agent-config route for a program
  that still needs the bridge).
- Measured 2026-09-26 (JDK 25, default output, against the pre-a12 compiler that embedded the
  bridge as base64): `(print (java:static "java.lang.Math" "max" 3 7))` 101,803 -> 6,839 bytes; a
  13-site all-resolved program 103,527 -> 14,411; a program that still leaves sites to run time
  GROWS (127,454 -> 141,811: the bridge plus the site methods). Since a12 the bridge is a
  37,099-byte `$JavaBridge.class` beside the program instead -- written only when a site needs it.
  `Math.max` on a declared-int loop variable, 1M calls after warm-up: bridge ~95-220 ns/call,
  direct 1-2 ns/call (the loop floor: the JIT inlines it).
- Known resolution-status difference: the compile path folds some forms before the resolver sees
  them (`(code-char 128512)` -> a literal), so such a site resolves compiled and not interpreted;
  the member is the same either way.

## Implementing interfaces (java:reify, java:proxy, a function where an interface is expected)
- ONE rule, `compiler/JavaImplementations` -> `JavaImplementation` (its `Slot`s: name, parameters,
  return type, the function index or `NONE`): the methods an implementing class DECLARES. The
  interface's non-static `JavaType.publicMethods()` (`Class.getMethods()` as declared, never
  re-resolved; `JavaExecutable.isAbstract()`) grouped by `name(params)` in key order, variants by
  return type -- so the result is lookup-order free and a compiled class deterministic.
  `java:reify`: a designator (name + optional `java:call` tag) must leave exactly one group of the
  interface or of Object's `equals`/`hashCode`/`toString`; else the error is the form's run-time
  error ("has no method", "names more than one method of", "is implemented twice", malformed tag).
  Every variant of a named group calls its function; an unnamed variant one declaration leaves
  abstract, or two interfaces default, throws `UnsupportedOperationException("java:reify: no
  implementation of I.m(p)")`; a default keeps its body; Object's three are identity /
  `#<java-reify I>` unless named. `java:proxy`: every group but Object's three (default methods
  too -- java:proxy's meaning, unchanged) calls the callable with the name first; `toString` is
  `#<java-proxy I>`.
- `(java:proxy "I" "J" ... callable)` (b59, 2026-10-02): every name before the callable is an
  interface (each an interface, each once -- `repeatedInterface`); the groups are the union of
  the interfaces' methods by key, so `Consumer.accept(Object)` and `IntConsumer.accept(int)`
  are two slots and a key both declare one, all calling the one callable by name -- the
  Clojure proxy's per-name body. `JavaImplementation.interfaces()` is the list (reify: one;
  unresolved: empty); `interfaceNames()` (space-joined) is `toString`'s `#<java-proxy I J>`
  and the messages' interface text. Interpreter: one `Proxy` over all of them, defined in the
  first interface loader that sees every one (`proxyLoader`, the bridge's twin). JVM: one
  `$Proxy<N>` implementing each; the bridge's `javaProxy(Object, Object[])` takes the first
  name, then the rest and the callable (the `reify` descriptor). Kind:
  `JavaImplementationType` holds the list, canonical per list in each lookup
  (`implementationOf(List)`; the single-interface overload is the one-element list); it is
  assignable to each interface, but `single()` is null for several, so
  `JavaStaticType.receiverClass()` is null (a call ON the object is left to run time --
  `--java-static` refuses it) and `specOf` spells nothing. A direct call's kind test is
  `instanceof $Implementation`, `instanceof` each interface AND `getClass().getInterfaces().length
  == n`, so a declared `(java:object "I" :exact)` holding a proxy of I and J is refused on the
  JVM as the interpreter's identity kind check refuses it. Pinned:
  `JavaImplementationPrograms.PROXY_SEVERAL` / `_PASSED` / `FALSE_SINGLE_IMPLEMENTATION` in
  `JavaInteropTest` and `JvmJavaInteropCompilerTest`, `JavaImplementationsTest`, the parity
  test's `proxySlots(Class[])` corpus. A superclass is not an interface: a proxy over a class
  needs a generated subclass on the interpreter too (no `Proxy` can) -- `java:subclass`,
  next section.
- Dispatch key is `name(params)return` (`Slot.dispatchKey`): a covariant default variant is never
  shadowed by an abstract sibling.
- A value a function RETURNS is marshalled to the method's return type as an argument is, EXCEPT
  a function is never made a proxy (interpreter `marshal(..., proxies=false)`, the bridge's twin,
  the direct sites' returned `_jcost$N` / `_jconv$N`). Decided 2026-09-26 on a measurement: with
  the function arm, a return conversion to an interface needs that interface's proxy class, whose
  methods' interface returns need theirs -- one `java:reify` of `CharSequence` pulled in 19 proxy
  classes (the IntStream/Stream/Spliterator family, ~60 KB) and a 169 KB program class. Clojure's
  reify/proxy do not coerce return values either; an interface return is a
  `java:reify`/`java:proxy` object.
  Function -> interface stays for ARGUMENTS (Clojure 1.12's direction).
- `:functional` (e66, 2026-10-08): a `java:new` / `java:call` / `java:static` whose LAST
  element is the keyword (`LispNames.JAVA_FUNCTIONAL_MARKER`, after the arguments; a
  keyword is never an argument a member takes) converts a function argument by
  `JavaImplementations.functional` instead of `proxy`: a reify whose one function
  implements every group with an abstract variant (Object's three aside, each variant of
  the group), defaults keep their bodies, `#<java-reify I>`. COSTS are unchanged (a
  function still costs `COST_PROXY` against any interface), so overload choice and the
  memos are the same in both modes; only the conversion differs. Resolved sites:
  `JavaSiteResolver.resolve` strips the marker and answers `JavaSite.functional()`;
  the interpreter's `evalJavaSite` drops the evaluated marker and passes
  `JavaInterop.functional(caller)` (`Caller.functional()`, read by `convert`'s function
  arm); the direct sites' `Body.functional` picks `functionalFactory` in the FUNCTION
  arm and a `_jconv$N` keyed `" functional"`, and `shapeKey` carries it (two sites
  differing only in the marker are two `_jsite$N`: the program's last two calls pin it).
  Run-time paths read it off the evaluated
  arguments (`JavaInterop.endsFunctional`, the bridge's `functionsOf`: a keyword compiles
  to its name, `":FUNCTIONAL"`), so `apply #'java:call` takes it too; the bridge threads
  `FUNCTIONS_NONE/PROXY/BY_ARGUMENTS` where it threaded `proxies`. `java:field` takes none;
  `java:subclass` reads it after its callable, for its constructor arguments
  (`JavaImplementations.subclassFunctional`; the interpreter's `subclass` drops it and
  passes `functional(caller)`, the JVM's `subclassFactory(..., functional)` keys a second
  `$Subclass<N>` whose `_jsubclass$N` converts through `argumentConvert(param, functional)`).
  The Clojure lowering ends every
  host call with a non-literal argument in it (`ClojureInteropLowering.hostCall`). Pinned:
  `JavaImplementationPrograms.FUNCTIONAL` (both backends; resolved, dispatched, bridge,
  constructor, static, a default method), `JavaImplementationsTest#aFunctionalImplementation...`,
  `JavaBridgeTemplateParityTest#theTemplateImplementsAFunctionalArgument...`,
  `JavaSiteResolverTest#aTrailingFunctionalMarkerIsSetAside`.
- The object's KIND is `compiler/JavaImplementationType` (canonical per interface in each lookup,
  `JavaClassLookup.implementationOf`): assignable to Object, `java.io.Serializable`, the interface
  and its superinterfaces -- a `java.lang.reflect.Proxy` class's supertypes less `Proxy` -- so its
  cost depends on the interface alone and a call passing a literal `java:reify`/`java:proxy`
  RESOLVES (`JavaSiteResolver.typeOf`); `receiverClass()` is the interface. Spelled
  `(java:object "I" :exact)` (no object's class is exactly an interface, so `:exact` of one means
  this), so a `let`-bound listener keeps it: `(let ((l (java:reify ...))) (java:call b "add..." l)
  (java:call b "remove..." l))` resolves both. The interpreter maps its OWN Proxy objects to the
  kind (`JavaInterop.hostKind`, the handler's `Dispatch.kind`); `Argument.expected()` reads "an
  implementation of I".
- Interpreter: `JavaInterop.reify`/`proxy` -> a `Proxy` whose `ImplementationHandler` dispatches on
  the slots (memo per `Method`), `InvocationHandler.invokeDefault` for an unnamed default. The
  resolution is cached per (interface, designators). `java:reify` is a plain function there
  (computed names work; errors after the arguments are evaluated, as a call).
- JVM (`JvmJavaImplementations`, owned by `JvmJavaSites`): a form `JavaImplementations.resolve`
  resolves (literal names, interface found and linkable, every slot return type linkable) is
  `invokestatic <Program>$Reify<N>.of(Object[] functions)` / `$Proxy<N>` -- one class per
  (interface, slots) shape, the functions evaluated left to right. The classes extend ONE
  per-program `abstract <Program>$Implementation implements Serializable` (the `fns` field and
  constructor), which is what a direct call tests an argument counted as the kind against
  (`instanceof $Implementation && instanceof I`, as strict as the interpreter's kind check). A slot
  boxes its arguments as a Proxy does and calls a PACKAGE-PRIVATE program method
  `_jimpl$K(Object fn, Object[] args)R` (one per (proxy?, interface, dispatch key)): `_junm` each
  argument into a list (a proxy's with the name first), `_apply`, then the direct sites' RETURNED
  `_jcost$N` / `_jconv$N` for R (`returnedCost` / `returnedConvert`: the per-type helpers of
  dispatched sites, without the function arm) or the interpreter's "cannot return" text. The
  `_jimpl$` names are shaker roots and pinned to the main class on a split
  (`JvmLispCompiler.implementationCallbacks`); `bridgeClassFiles` carries the classes, stamped with
  the program's class version. Anything else (computed names, an interface not found, an
  unlinkable one) is the bridge's `javaReify` / `javaProxy` -- a `Proxy` over `reifySlots` /
  `proxySlots`, a hand copy pinned by `JavaBridgeTemplateParityTest`; `--java-static` refuses it
  ("it implements its interface with java.lang.reflect.Proxy: <reason>"), and
  `--warn-java-reflection` / `java:*warn-on-reflection*` report it on both paths.
- NOT LambdaMetafactory (the a16 plan): an `invokedynamic` implements only a functional interface's
  one abstract method, and routes no default method -- java:proxy's meaning and an auto-proxied
  argument route every method to the callable, and a non-SAM interface (`MouseListener`) must
  work; one generator serves reify, proxy and arguments; plain classes need nothing from
  native-image nor `am.ik.jvm` indy support (BootstrapMethods, the shaker, the splitter).
- Known differences: the object prints as its class (`#<java Prog$Reify0>` compiled, a
  `jdk.proxyN.$ProxyM` interpreted -- as java:proxy objects always did); a parameter typed
  `java.lang.reflect.Proxy` accepts the interpreter's object only; a bridge-made object (a form
  left to run time) fails a direct call's `$Implementation` test, which only a false `(java:object
  "I" :exact)` can bring about.
- Measured 2026-09-26 (JDK 25, `-o P.jar`): `(java:call (java:proxy "java.util.function.Supplier"
  f) "get")` 12,916-byte jar (29,411-byte class + 238 + 596 generated) vs the bridge path it took
  before (the same with the interface name in a variable) 40,070 (51,335 + 45,528: the bridge
  forces the eval runtime). A `Supplier.get` through a typed receiver, 1M calls: generated class
  9-55 ns/call, bridge Proxy 146-315 ns/call; interpreter ~1.2-2 us either way (its loop floor).
  Native image with no configuration: a 9-form `--java-static` program (a `PropertyChangeListener`
  the JDK calls back, a comparator `Collections.sort` calls and its default `reversed()`, a
  `Runnable` on a thread, a `forEach` lambda, a literal proxy, `IntBinaryOperator`) built in 26 s,
  output identical to `java -jar`
  (`ShippedBridgeNativeImageE2eTest#anInterfaceImplementingJavaStaticJarRunsAsANativeImageWithNoConfiguration`).

## Extending a class (java:subclass, b71)

- `(java:subclass "S" '(\"I\"...) '(\"m\"...) ctor-args... callable)`: one object extending
  the superclass `S` (and the extra interfaces `I...`), each named method calling the
  callable as `(callable this name args...)` -- `this` first, then the name, so a Clojure
  proxy body sees its object and a `proxy-super` reaches the superclass implementation
  through the generated `super$m$a` accessor (one public method per named (name, arity),
  calling the most derived concrete declaration, else an interface default; none when no
  superclass implementation exists). The constructor arguments choose the superclass
  constructor by THE rule (`JavaOverloads` over the public and protected constructors),
  always when the form runs on the interpreter, by a generated program-side dispatch
  (`_jsubclass$N`, the dispatched-site scan over the same overloads) compiled.
- One rule, `compiler/JavaImplementations.subclass` over `JavaType.overridableMethods()`
  (the public or protected non-final instance methods of the class chain, bridge and
  synthetic ones never, most-derived wins, covariant variants kept) and
  `subclassConstructors()` (public and protected): both lookups implement both, pinned by
  `JvmClassFileLookupTest#everySuperclassIsSubclassedTheSame`. A named method is a slot
  calling the callable; an unnamed concrete class method is inherited (it calls super);
  an unnamed abstract one -- and an unnamed interface method -- is a slot throwing
  `UnsupportedOperationException` with the method's name (the oracle). `toString` /
  `equals` / `hashCode` override like any other class method (unlike `java:proxy`, which
  keeps `Object`'s three).
- The object's KIND is `compiler/JavaImplementationType` with a superclass (canonical per
  superclass and interface list in each lookup): assignable to the superclass, its chain
  and the interfaces; `single()` is null, so a call on it and a `(java:object ... :exact)`
  spell nothing and resolve by its class when they run, while passing it where the
  superclass is expected resolves (a `WIDEN`). A direct call's kind test is `instanceof`
  the superclass (but not exactly it) and each interface, with exactly the extra
  interfaces directly implemented -- as strict as the interpreter's identity kind check.
- Interpreter (`eval/JavaInterop`, `eval/ClassProxyMaker`): the subclass is defined at run
  time (`java.lang.classfile`, one child loader per class, the dispatch interface
  `eval/ClassProxyHandler` typed with JDK types only); the constructor is chosen over the
  argument kinds, never remembered. Interpreted native image stays refused as today.
- JVM (`JvmJavaImplementations`, owned by `JvmJavaSites`): one `$Subclass<N>` per
  (superclass, interfaces, slots, arity) shape, one constructor per viable superclass
  constructor overload (linkable parameters), the `fns` field, public overrides calling
  the program-side `_jsub$K(Object fn, Object self, Object[] args)` callbacks (the
  `_jimpl$K` shape with `this` and the name first, shaker roots and split-pinned the
  same), no default `toString`. A form left to run time is refused by name (the bridge's
  `javaSubclass` throws; `--java-static` refuses at codegen naming the reason) -- the
  interpreter resolves it when it runs, so computed names diverge by backend, documented
  in the guide.
- A project class the compile class path lacks resolves the same way: the interpreter
  loads it, the compiler leaves the form to run time (bridge refusal), so a class proxy
  over one needs `--java-classpath`.
- Tests: `testsupport/JavaImplementationPrograms.SUBCLASS` (both backends),
  `JavaInteropTest` / `JvmJavaInteropCompilerTest` (resolved errors alike, unresolved
  refusals, `--java-static`), `JavaImplementationsTest` (slots, resolution, kinds),
  `ClojureInteropTest` (the `File`, `proxy-super`, `paintComponent`, snake and SAX
  shapes) and `ClojureWasmInteropRefusalTest` (both wasm backends refuse `java:subclass`
  with the undefined-function call-time error, like every `java:` leg); user doc
  `guides/java-interop.md` ("Class proxies via java:subclass"), `reference/functions/
  java-subclass.md` and the Clojure `proxy` page.

## The program's Java class path (`--java-classpath`, `--java-dep`)

- ONE carrier: `eval/SourceLoader.javaClassLoader()` (default rontolisp's own loader;
  `SourceLoader.fileSystem(ClassLoader)`). The evaluator derives its lookup from its source
  loader (`setSourceLoader` -> `ReflectiveJavaClasses.over(loader)`, clearing the site memo),
  and `JavaInterop.Caller.classes()` is what every name-to-class step uses (`loadClass`,
  `field`, `keepsPromise`); a class's kinds and implementation types stay canonical per Class.
  `ClassProxyMaker` keys a generated subclass by the classes themselves (was: their NAMES, a
  collision across loaders) and defines it under the program loader. The Clojure lowering's
  reflection (arities, throwable chains, `Class/member` static-or-field) goes through
  `clojure/ClojureHostClasses.load`, bound per lowering from `ClojureFiles.javaClassLoader()`
  (`Clojure.read`, `ClojureSession.read`; a ThreadLocal, since helpers deep in the lowering
  have no context, and the lowering runs to completion where it starts). The compile path
  hands it in `CompileFrontend.Request.javaClassLoader`.
- CLI (`cli/JavaClassPath`): `--java-classpath` entries (must exist, else refused by name)
  then the `--java-dep` jars in Maven's runtime class path order (`MavenResolver.resolve`,
  `.kb/maven-resolver.md`), from Central, then the repeatable `--java-repository [ID=]URL`
  repositories in the order given (`JavaResolutionOptions.repositories`; no id ->
  `java-repository-N`, an id given twice refused, `central` replaces Central's URL in place,
  the flag without `--java-dep` refused; Central stays first because a mirror in
  `settings.xml` is the way to stop asking it; the active `settings.xml` profiles' repositories
  go AHEAD of both, in Maven's order, `.kb/maven-resolver.md` "Repositories"), through `settings.xml`'s (user's over `$MAVEN_HOME`'s)
  local repository / `offline` / mirror / proxy / server (`RontoLispCli.javaDependencyResolver` injects a fixture repository, or a factory over the repository list, in tests),
  then the jars a Clojure program's `deps.edn` dependencies bring, holding classes
  (`JavaClassPath.add`, reached through `SourceLoader.addJavaClassPath` /
  `SourceLoader.fileSystem(loader, sink)` and `CompileFrontend.Request.javaClassPath`, as the
  lowering resolves its project: `.kb/clojure-frontend.md` "deps.edn"), each once, its Maven
  coordinate joining `dependencies()` for a generated pom. The loader is a
  `URLClassLoader` subclass over rontolisp's loader, made once and ALWAYS (a run with no entry
  gets an empty one, so a lowering can grow it: `addURL`) -- not in a native image (no
  run-time class definition: there the class path reaches the class-file lookup and the
  outputs only). A wasm compile's class path names nothing (`--java-classpath`/`--java-dep`
  are refused there) and grows the same way: its lowering and its macro time run on the JVM.
  The Clojure macro-time evaluator resolves `java:` through the program's loader
  (`ClojureMacroTime.create(loader)`, from `SourceLanguage`/`SourceSession`), so a helper a
  macro body calls reaches what the program does -- until 2026-10-08 it had rontolisp's own
  (`No such class` in the macro for a dependency's class). Growth lands before the lowering's
  first class question and before the program runs: `ReflectiveJavaClasses` remembers an
  absent name, so a class asked for before its jar joined stays absent (a CL program that
  `load`s a `.clj` after asking). `JvmSourceCompiler` (an embedder) lowers against a
  `JavaClassPath.of(javaClasspath)` it closes after the compile, the grown entries
  resolving the backend's sites. `--java-release` / `--java-static` stay compile-only; the
  class path is the interpreter's and the REPL's too (until 2026-10-08 the interpreter
  refused `--java-classpath`: a jar could not be added under `java -jar` at all).
- Outputs: a program jar copies each entry into `<stem>-lib/` beside it (a jar under its file
  name, a directory as a directory; two entries of one name refused) and lists them in the
  manifest `Class-Path` (relative URLs) -- exact class-path semantics for `java -jar` and
  `native-image -jar`, unlike bundling (services, signatures, multi-release, `getResources`).
  A `--no-main` library jar carries none: its pom lists the `--java-dep` coordinates
  (`MavenCoordinates.pomXml(simd, deps)`; a `--java-classpath` entry is the consumer's). A war
  packs jars into `WEB-INF/lib/`, a directory's files into `WEB-INF/classes/` (a collision
  with the program's own entries refused). A `.class` carries nothing (`java -cp`).
- Pins: `JavaInteropTest#aClassOnTheProgramsJavaClassPathIsReachable`,
  `JvmJavaInteropCompilerTest#aClassOnTheJavaClassPathIsCalledDirectly` (the shared
  `testsupport/JavaLibraryJar` program: new, static, field, reify with a default method,
  subclass, a site left to run time), `cli/JavaClassPathCliTest` (interpreter, `.class`,
  program jar after the source jar is deleted, war, `--java-dep` nearest-wins through a
  `file:` repository + pom, library jar, a Clojure program's `(Class/zeroArgStatic)`,
  refusals). WASM: unchanged (`java:` refused at call time).

## What a callback raises passes through the Java call
- A function called back from Java (a `java:reify` / `java:proxy` object's method, a function
  passed where an interface is expected) runs inside a Java call a `java:` site made. What
  LEAVES it thrown -- an exit (`return-from`, `throw`, `go`, `invoke-restart`), a condition of
  any type, a raw failure, `uiop:quit`, the refusal of its value (`cannot return X as T`) -- is
  recorded on its way out, and the site whose Java call throws THAT throwable throws it on
  unchanged; anything else is still `error calling C.m: <throwable>`. Clojure's reify
  semantics. The `UnsupportedOperationException` of an abstract method no function implements
  is the object's own Java-level failure: not recorded, wrapped as before.
- Measured 2026-09-26 before: every one was wrapped -- a `return-from` out of `List.forEach`'s
  callback never arrived (`error calling java.util.List.forEach:
  am.ik.rontolisp.eval.BlockReturnSignal`, compiled `java.lang.RuntimeException`), a
  `handler-case` on the condition's own type never matched on the interpreter, a restart
  established outside the call could not be invoked from inside it (interpreted: `...
  ThrowSignal`), and the text named each backend's exception class.
- Why a record, not a class test: the interpreter's signals are its own classes
  (`LispEvalException`, `BlockReturnSignal`, `ThrowSignal`, `GoSignal`, `LispExitSignal`),
  which no Java code throws, but a compiled program's are JDK classes (`RuntimeException` for
  `error` and every exit, `ClassCastException` / `ArithmeticException` / ... for a raw failure)
  a Java method throws too. The boundary is the one place that knows a throwable is Lisp's.
  Both backends keep the record, so they agree on what Java does with it.
- The record: per thread, newest first, at most `JavaImplementations.PENDING_SIGNALS` (16). A
  site that finds the throwable drops it and every NEWER entry: those left their callbacks
  after it and Java swallowed them while it was on its way out (`Stream.close` runs every
  close handler and relays the first one's throwable with the rest suppressed, so a single
  slot would hold the second and lose the first). What Java catches and ignores stays until 16
  newer push it out; what it wraps (`FutureTask.get`'s `ExecutionException`) or rethrows on
  another thread (ForkJoin may re-create it with the original as its cause) is a Java failure
  like any other -- the compiled per-thread channels below could not follow it to another
  thread anyway.
- Interpreter: `JavaInterop.ImplementationHandler.invoke` -> `raised` (the whole Lisp side:
  argument unmarshalling, the call, the return conversion); `fail` -> `passedOn` (`RAISED`).
- Compiled: `_jsig(Throwable)Throwable` is the handler of every `_jimpl$K` (its whole body)
  and of the bridge's Proxy (`callback` / `signal`, bound in `bind`). Every site's failure
  handler is `ldc text; invokestatic _jfail(Throwable,String)Throwable; athrow` -- one method
  holds the rule (the inline wrap it replaced took ~20 bytes of code per site method, the call
  takes ~7), and
  the bridge's `fail` calls it too (bound in `bind`, like `_apply`; missing = a loud bind
  error). The record is `_jsigTl`, a ThreadLocal of `Object[]{throwable, condition, exit entry,
  previous}`, declared and initialized in `<clinit>` only when a callback can exist (a
  `_jimpl$K`, or the bridge). `JvmJavaDirectSites.finishHelpers` builds both helpers after
  every body is compiled, since what they read depends on the channels the program has;
  `_jsig`/`_jfail` are `REFLECTIVELY_FOUND_METHODS` and, with the bridge, shaker roots.
- Compiled custody of the two per-thread channels a condition or exit also lives in (the
  interpreter's signals carry their own state): `_jsig` takes the throwable's own condition off
  `_condTl` (keyed by the throwable: [error-handling.md](error-handling.md), "The JVM keeps what
  a throwable carries under the throwable") and its own entry off the `_nleTl` exit stack;
  `_jfail` puts both back for the throwable it passes on, and a wrapped failure is a new
  throwable no condition is recorded for. Measured 2026-09-26 without it: the `Stream.close`
  relay answered the SECOND handler's condition and lost the first handler's exit
  (`%nlx-catch` reads only the top entry: `Unhandled condition: null`); a condition
  `FutureTask.run` swallowed was read by the next `handler-case` on the thread, for the `get`
  failure and for a later Lisp `type-error` alike. Until `_condTl` was keyed (the same day), a
  Java call made from a cleanup while a typed condition was on its way out, whose callback
  raised a PLAIN throwable Java swallowed, took the outer condition with it (`_jsig` took
  whatever the one slot held): the last `CALLBACK_SIGNALS` row.
- Cost, measured 2026-09-26 (JDK 25): the normal path is unchanged (an exception-table entry):
  a 200,000-element `Collections.sort` through a `java:reify` comparator takes ~37 ms before and
  after compiled, a 20,000-element one ~71 ms interpreted. Class bytes before -> after:
  `(print (java:static "java.lang.Math" "max" 3 7))` 6,916 -> 7,030 (`_jfail`); a direct
  `forEach` with a lambda 30,135 -> 30,783 (`_jsig`, custody, `_jsigTl`); java-interop.lisp
  59,289 -> 59,802 and its bridge 47,726 -> 49,151; life-gui.lisp 107,098 -> 107,553.
- Pins: `JavaInteropTest` / `JvmJavaInteropCompilerTest#whatACallbackRaisesPassesThroughTheJavaCall`
  over `testsupport/JavaImplementationPrograms.CALLBACK_SIGNALS` (exits, typed and raw
  conditions, a restart, a nested call, a site left to run time, a bridge Proxy,
  `Stream.close`, `FutureTask`), `JvmLispCompilerSplitTest#aForcedSplitKeepsJavaCallsWorking`,
  `ShippedBridgeNativeImageE2eTest` (the agent-config program binds `_jsig`/`_jfail`; the
  `--java-static` one needs no configuration for them); user doc `guides/java-interop.md`,
  "Errors and non-local exits".

## What a member throws: `java:java-exception`

**A failed call signals `java:java-exception`, a `simple-error` (report `error calling C.m:
<throwable>`, as before) whose slot 2 (`%JAVA-EXCEPTION-CAUSE`) holds the throwable;
`java:java-exception-cause` (prelude) answers it.** Handed back to a member -- an argument of a
dispatched or run-time site, the receiver of a run-time `java:call` -- one is that throwable.

- The class is seeded on demand (`ClosRegistry.ensureJavaExceptionSeeded`, never in
  `CONDITION_SEEDS`: a registered class joins every `error` typep table): by the interpreter in
  `registerJava`, by the JVM backend for a program naming a `java:` operator, by
  `expandTopLevelDefinitions` for one naming the class or its reader. Never for wasm otherwise,
  so a wasm program is byte-identical.
- Interpreter: `JavaInterop.fail` throws `LispEvalException.ofClass(JAVA_EXCEPTION)` with the
  throwable as its Java cause; `synthesizeCondition` (every landing and seam) builds the instance.
- JVM: `_jfail` records the throwable under the `RuntimeException` it makes in `_jexMap`, a
  `Collections.synchronizedMap` over a `WeakHashMap` (one per class: the record follows the
  exception across an await or a join); `_hcSynth`'s first arm builds the instance from it.
  Both exist only where a `java:` program has a landing. Not the exception's cause: the default
  handler would print the cause's frames under the one-line uncaught report.
- Conversion back (`JavaInterop.hostArguments`/`hostException`, registry-free: slot 2's name;
  JVM `_jexc`, an outlined Lisp body called at each unknown-kind argument of a dispatched site,
  each argument of a bridged one and a bridged receiver): slot 2 holds the throwable, or a
  function building it, which is called with the condition -- the protocol an exception of the
  Clojure runtime uses (`.kb/clojure-frontend.md`, "Host exceptions"); `java:java-exception-cause`
  reads it the same way. `_jexc` exists only where a condition can be held
  (`routesConditionReports`) and the class is registered.
- Cost, JVM: a caught failure +1.3 us (200k `handler-case` iterations 0.69 -> 0.93 s; the weak
  record alone 0.2-0.8 us); a CL `java:` program with a handler-case +0.3-0.5 KB of class.
- Pins: `JavaInteropTest` / `JvmJavaInteropCompilerTest#aFailedCallSignalsAJavaExceptionCarryingWhatTheMemberThrew`
  over `testsupport/JavaInteropPrograms.HOST_EXCEPTION_PROGRAM`,
  `ClojureProjectNamespacesTest#aClojureExceptionCaughtInCommonLispCarriesItsHostException`; user doc
  `guides/java-interop.md`, "Errors and non-local exits", `reference/functions/java-exception-cause.md`.

## Tests / docs
`JavaSiteResolverTest`, `JavaDeclarationsTest`, `JavaImplementationsTest` (compiler),
`JvmClassFileLookupTest` (incl. `everyInterfaceIsImplementedTheSame`),
`JavaBridgeTemplateParityTest`, `am.ik.jvm.JvmClassPathTest`; direct calls:
`JvmJavaInteropCompilerTest` (javap shape, class version, `--java-static`, conversions, texts,
dispatch: `aDispatchedSite*`, programs shared with `JavaInteropTest` through
`testsupport/JavaInteropPrograms`; the reify/proxy programs, callback signals included, through
`testsupport/JavaImplementationPrograms`),
`JvmLispCompilerSplitTest#aForcedSplitKeepsJavaCallsWorking`,
`JvmDeadMethodEliminationTest#keepsTheCallbacksOfAGeneratedInterfaceImplementation`,
`ShippedBridgeNativeImageE2eTest`. `JavaInteropTest` + `JvmJavaInteropCompilerTest` mirror the
same cases — keep in step, headless only. `examples/jvm/{java-interop,swing,life-gui}.lisp`;
`doc/{en,ja}/guides/java-interop.md` + six `reference/functions/java-*.md` (a GUI form hangs
`DocExamplesTest`).
