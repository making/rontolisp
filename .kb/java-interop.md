# `java:` interop (interpreter, JVM direct calls, generated interface classes, the reflection bridge)

Package `java` (`LispNames.JAVA_PKG`; does NOT use `cl`): operators in
`LispNames.JAVA_OPERATORS_QUALIFIED`, the type specifier `java:object`, `java:*warn-on-reflection*`.

- Interpreter: `eval/JavaInterop`; value = `LispJavaObject`, prints `#<java <class>>`.
- JVM: a RESOLVED site is a direct call ("Direct calls"); a resolved `java:reify` / `java:proxy`
  and a function passed where an interface is expected an object of a generated class
  ("Implementing interfaces"); every other site goes through `codegen.jvm.JavaBridgeTemplate`,
  which re-implements the run-time half against the compiled representation — **KEEP THE TWO
  IN SYNC**. `JvmJavaRuntimeBuilder` ships it as `<Program>$JavaBridge` only when a site needs
  it; `bind(Class)` stores that program's `_apply` statically. It needs JRE >= build JRE.
- Trap: the template must have NO nested classes/records and NO rontolisp imports. The bridge
  forces `usesEval`, a generated interface class only the apply tier (`JvmJavaSites.needsApply`);
  `usesJava` threads `JvmRuntimeBuilder.JavaPrint` into the print builders.
- Native image: template `.class` in `resource-config.json` — COMPILE works, INTERPRET does not.
  A Clojure host form lowers there against class files, not the image's partial reflection
  (`.kb/clojure-frontend.md`, "Host classes"); the examples suite skips the interpreter leg of a
  `javaInterop: true` example under the binary (`.kb/running-backends.md`). A `-o prog.jar` whose
  sites all resolve native-images with NO config (`--java-static`); one needing the bridge needs
  agent config, which covers only traced overloads (an untraced `Math.max(double,double)` is
  `MissingReflectionRegistrationError`). `ShippedBridgeNativeImageE2eTest`, opt-in
  `-Drontolisp.native-image.e2e=true`.
- WASM: rejected; no `BuiltinFunctionWrappers` entry, so `#'java:call` is a compile error while
  the interpreter allows it.
- No host value is a Lisp hash table: a table is the travelling `runtime/RontoHashTable` (a
  `LinkedHashMap` would be shared with host maps).
- `select()` = lowest total cost `COST_EXACT` < `COST_WIDEN` < `COST_CONVERT` < `COST_NARROW` <
  `COST_BOXED` < `COST_PROXY` < `COST_FUNCTION_OBJECT` < `COST_PROXY_NOT_FUNCTIONAL`
  (`COST_VARARGS` via `varargsCost`), ties by signature string, then the most specific covariant
  return -- never the bridge method that erases it. Symbols (but `|false|`), ratios, dotted lists
  and rank-2+ arrays are NOT marshalled; a `java:handle` stands for any value.
- THE rule lives ONCE: `compiler/JavaOverloads` (`KindRule` table, `ruleTable`,
  `kindCost`/`returnedCost`, `isReceiverKind`/`receiverClassName`, scalar and checked
  conversions, `select`/`selectRanked`). The interpreter selects through it at run time over
  `compiler/ReflectiveJavaClasses`; `JvmJavaDirectSites` reads it at compile time and emits
  `_jkind`/`_jcost$N`/`_jconv$N`/`_jrecv`/`_jchk$N`; the standalone bridge reads the serialized
  `ruleTable()` into `KIND_RULES`. A new cost/receiver rule lands in `KindRule`/`buildRules` plus
  the mechanical `RULE_TABLE` copy; a new scalar conversion in the `convert*` helpers plus the
  bridge's hand arms. Pinned by `JavaBridgeTemplateParityTest`
  (`theBridgeSpellsTheRepresentationAsTheRuntimeDoes` the table string,
  `theBridgeReadsTheSharedRuleTable`, `theBridgeConvertsScalarsAsTheSharedRuleDoes`, plus the
  resolution, receiver, unmarshal and handle corpora). `LispJavaObject.receiverObject` stays a
  hand copy (the root package cannot import `compiler`: package cycle), pinned by the receiver
  corpus.
- `accessibleMethod` (all three lookups: `ReflectiveJavaClasses`, the bridge,
  `JvmClassFileLookup`) re-resolves a public method whose declaring class cannot be called
  through to the same method on an accessible supertype, the walk starting at the RECEIVER class
  (as Clojure's Reflector). Starting at the declaring class misses HashMap's entry iterator
  (`hasNext` from `HashMap$HashIterator`, which implements nothing).

## What a host object is (one rule; interpreter = `LispJavaObject`)
- Compiled: `JavaBridgeTemplate.isJavaObject` = `JvmJavaDirectSites._jhost`, test for test
  (`JavaBridgeTemplateParityTest#theBridgeAndADirectSiteCountTheSameHostObjects`): NOT a
  `Long`/`Double`/`BigInteger`/`String`, NOT any Java array (a Java array a call answers is
  unmarshalled), NOT exactly an `ArrayList` whose slot 0 is an `Object[]` (a Lisp array), NOT a
  class in `am.ik.rontolisp.runtime` -- but a handle or view (`RontoJavaValue`) is one.
- `_jltab` compares the class NAME, never `instanceof`: that loads `RontoHashTable`, which travels
  only with a hash-using program (`NoClassDefFoundError` otherwise,
  `JvmRuntimeClassReferencesTest`). `_jlarr`, the bridge's `lispArrayHeader` / `rendered` and
  `_strv` test the EXACT class (`getClass() ==`) before reading: a host subclass's own `isEmpty`
  / `get` can lie. In a `java:` program only (`Ctx.javaSites`, `JavaPrint`) the printer's array
  arm, `%arrayp`, `stringp` and `%simple-array-p` call them
  (`JavaImplementationPrograms.HOST_COLLECTION_SUBCLASS`); the hash-table arm and `_hashP` need
  only `instanceof`. Pin: `JavaInteropPrograms.HOST_COLLECTION_PROGRAM` (both backends).
- The bridge's `describe` is the program's `_lispToString`, bound in `bind` (a shaker root while
  the bridge travels; `REFLECTIVELY_FOUND_METHODS` keeps it on a split), so a run-time site
  describes values as the interpreter and a direct site do.
- A Java `BigInteger` result is a Lisp integer on all three paths (a fixnum when `bitLength() <
  64`); `JavaStaticType.becomesLisp` counts BigInteger, its supertypes and subclasses. Why: the
  compiled representation cannot hold a host `BigInteger` apart from a bignum. Pin:
  `testsupport/JavaInteropPrograms.HOST_OBJECT_PROGRAM`.
- The ACCESSORS refuse a host collection as the interpreter does, in a `java:` program only:
  `_jcktab(v, "OP")` (`JvmHashTableCompiler.emitHostTableGuard`; after gethash's default /
  `%puthash`'s value, which the interpreter evaluates first) and `_jckarr`
  (`JvmArrayCompiler.emitHostArrayGuard`), renamed by `_opTypeErr`. Only a host collection of the
  accessor's OWN class is refused here; any other wrong type is the every-program check
  ([error-handling.md](error-handling.md), "A sequence, array or hash-table operand of the wrong
  kind"). `_length`'s array arm asks `_jlarr`. Pin: `JavaInteropPrograms.HOST_ACCESSOR_PROGRAM`.
- `eq`/`eql` on a host object is identity, `equal`/`equalp` its `equals` (a Lisp value on the
  right converted by the receiver rule); the JVM's equality helpers ask `_jhost` / `_jrecv` in a
  `java:` program only ([eq-numbers.md](eq-numbers.md), "Host objects").

## A Lisp value as a `java:call` receiver (one rule, three copies)
- A receiver that is no host object but a Lisp value of a receiver kind
  (`JavaOverloads.isReceiverKind`: every kind but NIL and FUNCTION) is called as what `convert`
  makes of it for an `Object` parameter (fixnum the narrowest box, so `(java:call 5 "equals" 5)`
  is T; bignum `BigInteger`; supplementary char `Integer`; `t`/`|false|` the `Boolean`). Anything
  else keeps `java:call expects a java object ..., got X`; `java:field` is unchanged (a string
  there is a class name). Why: a string Java answered was otherwise a Lisp string no further call
  could take (Clojure `(.codePointAt "abc" 0)`).
- Copies: `LispJavaObject.receiverObject`, the bridge's `receiverObject`, `_jrecv`
  (`JvmJavaDirectSites.RECEIVER`, called only after `_jhost` refused, so a host receiver pays
  nothing). The resolver (`JavaSiteResolver.callReceiverClass`) resolves a receiver whose non-nil
  kinds all name one class among that class's methods.
- Pins: `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteCallALispValueAsTheSharedRuleSays`,
  `JavaInteropPrograms.LISP_RECEIVER_PROGRAM` (both backends), `HOST_OBJECT_OUTPUT`,
  `JavaSiteResolverTest#aLispValueReceiverResolvesAsTheClassItConvertsTo`.

## Bignums and specialized vectors
- BIGNUM is a kind: `BigInteger` EXACT, a supertype BOXED, else NO_MATCH -- no `long`, no lossy
  `double` (a user converts with `float`). INTEGER -> `BigInteger` is NO_MATCH (the oracle's
  reflector matches none for a `long`). Spelled `(java:object "java.math.BigInteger")` = {integer,
  bignum, nil} (`KIND_SPELLINGS`).
- A rank-1 SPECIALIZED vector is a sequence, its elements what `aref` reads; another rank no
  sequence, a quantized matrix no kind. Two copies: the bridge's `packedElements` (binds
  `_bf16Value` by name: a shaker root, in `REFLECTIVELY_FOUND_METHODS`) and `_jseq`'s
  `emitPackedElements`. Direct sites test only the shapes the program can hold
  (`JvmJavaDirectSites.packedVectors`).
- Pins: `testsupport/JavaInteropPrograms.SPECIALIZED_AND_BIGNUM_PROGRAM` (both backends),
  `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteReadASpecializedVectorAlike`,
  `JavaSiteResolverTest#bignumsAndBigIntegerParametersResolve`.

## Java's false and hash tables
- The symbol `|false|` (`LispNames.JAVA_FALSE`) is the kind `JavaKind.Lisp.FALSE`: t's twin,
  converting to `false` where nil is `null`; a receiver kind; a quoted `'|false|` types a site.
  Clojure's false object IS that symbol (`ClojureLowering.FALSE_VALUE_NAME`), so its false crosses
  with no Clojure name in `java:`. Host false unmarshals as nil unless the form ends in
  `:java-false` ("Markers"). `_jkind` tests `"false".equals` after `"T"`, so only a symbol that is
  neither pays it.
- A hash table converts, for a parameter a `java.util.LinkedHashMap` is assignable to, to a fresh
  one of its live entries in insertion order, `COST_BOXED` + their costs; an `equalp` table's key
  is the one first stored. Copies: `JavaInterop.marshalTable`, the bridge's `marshalTable` over
  `_hashValues` bound in `bind`, the direct sites' `_jtab` (only when
  `JvmJavaDirectSites.hashTables` named `_hashValues`).
- Pins: `JavaInteropPrograms.FALSE_AND_TABLE_PROGRAM` (`JavaInteropTest` /
  `JvmJavaInteropCompilerTest`), `HOST_OBJECT_OUTPUT`,
  `JavaSiteResolverTest#aQuotedFalseSymbolResolvesAsJavasFalse`,
  `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteReadAHashTableAlike`.

## Markers: `:java-false`, `:octets`, `:through`, a Comparator's answer
- Keywords ending a form are MARKERS (`compiler/JavaMarkers`: `:functional`, `:java-false`,
  `:octets`, `:through` with the function after it, any order; a keyword is never an argument a
  member takes): after the arguments of `java:new`/`java:call`/`java:static`, after the field name
  of `java:field`, after the callable of `java:proxy`/`java:subclass`/`java:reify`. Run-time paths
  read the evaluated markers (a keyword compiles to its name), so `apply #'java:call` takes them.
  Spellings pinned to `LispNames` by `theBridgeSpellsTheRepresentationAsTheRuntimeDoes`.
- `:java-false`: Java's false answers `|false|`, not nil -- a result, an array's elements, a
  field, and each argument a function of an implementation made at that form is handed. The
  static result type (a `boolean` answer is {T, FALSE}) keeps the next site resolving. Pins:
  `JavaInteropPrograms.JAVA_FALSE_PROGRAM`, `JavaImplementationPrograms.JAVA_FALSE` (both
  backends), `theBridgeAndADirectSiteAnswerJavasFalseAlike`.
- `:octets`: a `byte[]` the form answers (at any depth) is an `(unsigned-byte 8)` vector that IS
  the very array on every backend, so a later Java store (a `ByteBuffer`'s `array()`) is the
  vector's; a copy behind the width would miss it (`.kb/packed-integer-vectors.md`). Two answers
  of one `byte[]` are `eq`, and so are a `:bytes` view's vector and that array coming back: the
  interpreter keeps one `LispIntVector` per array (`JavaInterop.octetVector`, a `WeakHashMap` over
  `WeakReference`s -- the wrapper holds its array, so a strong value would never let the key go;
  `shareOctets` registers a view's vector). A Clojure byte array is a `(:C%BYTES octets)` wrapper
  made at each crossing, so `identical?` stays false (`.kb/clojure-frontend.md`, "Byte arrays").
  Trap: such a program turns `usesIntArray` on (`JvmLispCompiler.programAsksJavaForOctets`), else
  the answer prints `#<java [B>`. Pins: `JavaInteropPrograms.OCTETS_PROGRAM`,
  `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteAnswerOctetsAlike`.
- `:octets` in callbacks: a `byte[]` Java hands a FUNCTION of the form is Java's own array too, so
  the function's stores and Java's stores while it runs are shared. Compiled: `_jimpl$K` /
  `_jsub$K` of a slot whose parameters may carry a `byte[]` (`JavaOverloads.carriesBytes`) keyed
  `|octets`, unmarshalling with `_juno` / `_jufo`. Pins: `JavaImplementationPrograms.OCTETS`
  (`JavaInteropTest` / `JvmJavaInteropCompilerTest`),
  `JavaImplementationsTest#theMarkersAFormEndsInAreCarriedByItsImplementation`,
  `JvmLispCompilerSplitTest#aForcedSplitKeepsJavaCallsWorking`.
- A Comparator's answer: a function implementing `java.util.Comparator.compare` at a form ending
  in BOTH `:functional` and `:java-false` (`JavaImplementation.readsComparison`) is read as
  Clojure's `AFunction.compare`: `t` -1; `|false|` 1 when the function answers true for the
  arguments swapped, else 0; a number its low 32 bits / `d2i` / a ratio's `DECIMAL64` quotient
  truncated; nil the oracle's helpful-NPE (`JavaImplementation.COMPARISON_OF_NIL`), anything else
  the `ClassCastException` of its cast to `Number` (`comparisonCastFailure`). A refusal is the
  comparator's OWN failure, thrown outside the callback's record, so the site wraps it as a
  member's (a Clojure catch takes it by class). Copies: `ImplementationHandler.comparison`, the
  bridge's `comparison`, `_jcmp`.
- `:through f`: every function the form converts and every function of the implementation it
  makes is called by Java as `(f function kind resolved arguments)`, and the slot answers what `f`
  answers. `arguments` = what the function would be applied to (a proxy's with the name first, a
  subclass's with `this` and the name). `kind` (`JavaImplementation.answerKind`) = the primitive
  return type's keyword (`:boolean` ... `:double`), `:object` for any reference, nil for `void` and
  an `AFunction.compare` read. `resolved` = `t` when the converting site chose its member before
  it ran (`JavaSiteResolver.choosesItsMemberFirst`: one member, or dispatched over its only
  overload -- what Clojure's compiler resolves), else nil. Why: the one place a language learns
  whether and as what its answer is read -- Clojure's `%clojure-host-call` converts only for
  `:object` (a `void` method's lazy seq is never realized) and casts a primitive as the oracle's
  adapter does (`.kb/clojure-frontend.md`, "Java interop"). The `resolved` bit has no keyword and
  is kept only beside a `:through` (`withResolved`), so no other shape splits on it.
- The marker is VALUED: `JavaMarkers.count` / the bridge's `markerCount` take `:through` and the
  next element as one pair; a literal `#'name` is recorded by name, any other form is
  `THROUGH_COMPUTED` and leaves the form to run time, so a compiled program always knows the name
  (`JvmJavaInteropCompiler.prepareThrough` emits `_jthrough$N`, a direct call of it). Pins:
  `JavaImplementationPrograms.THROUGH` (`JavaInteropTest` /
  `JvmJavaInteropCompilerTest#javaCallsAFunctionThroughTheFunctionAfterThrough`),
  `JavaSiteResolverTest#aTrailingThroughAndItsFunctionAreSetAside` and
  `#aSiteTellsItsThroughWhetherItChoseItsMemberFirst`,
  `JavaBridgeTemplateParityTest#theBridgeHandsThroughTheAnswerKindTheSharedRuleNames`.
- The function object: a function converted at a form ending in `:functional` AND `:java-false`
  to `Runnable`, `Callable`, `Comparator` or `Object` is THE OBJECT THE FUNCTION IS
  (`JavaImplementations.functionObject`, `FUNCTION_INTERFACES`, `StandIn.FUNCTION`): one
  implementation of all three standing for the function, class name `clojure.lang.AFunction`
  (`FUNCTION_CLASS`). So two such objects of one function are equal (one fn is one object), and so
  are one and an identity `java:handle` of the function; Java hands it back as the function
  (`(.comparator (TreeSet. f))` is `f`), while one converted to any other interface stays an
  adapter of its own -- as the oracle (checked against clj 1.12.6).
- Where `Object` is expected at any OTHER call the conversion is REFUSED
  (`JavaImplementation.functionObjectRefusal`: `... needs a java:handle, got X`). Why: an empty
  stand-in would need the value runtime in every program with an unknown argument at an `Object`
  parameter, and one standing for nothing would hand back no function. The cost is
  marker-independent (memos are keyed by kinds alone), so a CL call passing a function where
  `Object` or a non-functional interface is expected chooses `Object` and is refused. Compiled:
  `Body.emitConvert`'s FUNCTION arm for `java.lang.Object` (`functionObjectFactory`), reached by
  every `_jconv$N` for `Object` incl. varargs/sequence/table elements; the shell `|stands`
  implements `RontoJavaValue`; `JvmJavaSites.makesStandIns` / `passesAFunction` predict it.
- Trap: a getter answers such an object through a DECLARED interface type, so in a program making
  `RontoJavaValue`s `mayHideALispValue` unmarshals every interface type and the view superclasses
  `AbstractCollection`/`List`/`Set`/`Map`, as the interpreter always did. The resolver still types
  the answer as the interface, so a direct `java:call` ON it refuses the function (both
  backends); Clojure wraps every receiver first. Pins: `JavaImplementationPrograms.FUNCTION_OBJECT`
  (`JavaInteropTest` / `JvmJavaInteropCompilerTest#aFunctionWhereAnObjectIsExpectedIsTheObjectItIs`),
  `JavaSiteResolverTest#aFunctionWhereAnObjectIsExpectedResolvesToTheObjectOverload`,
  `JavaBridgeTemplateParityTest#aFunctionCostsLessForAFunctionalInterface` and
  `#theTemplateImplementsAFunctionalArgumentAsTheSharedRuleDoes`,
  `ClojureInteropTest#aFnWhereJavaExpectsAnObjectIsTheFnItself`.
- `java:handle` of a function: `(java:handle function marker...)` with `:functional` and
  `:java-false` among the markers (`JavaImplementations.handleFunctionMarkers`) is that object made
  WITHOUT a call (`resolved` nil; a non-function `HANDLE_FUNCTION_USAGE`) -- for a Lisp-held object
  a function is (a view's item, a function's answer; the Clojure front end's
  `%clojure-host-member` of a fn), which no conversion at a call reaches. Compiled
  `JvmJavaInteropCompiler.compileFunctionHandle` -> the very shell a converting site of the same
  markers gets; a `:through` no `#'name` names is the bridge's `javaHandle` (`--java-static`
  refuses it, the reflection warnings report it). Rejected: a `:value` `java:reify` of the three
  in `clojure.lisp` (a second shell, the interfaces restated in Lisp) and a function flavour of
  `RontoJavaHandle` (a fourth copy of the comparison and the through call). Pins:
  `JavaImplementationPrograms.FUNCTION_HANDLE` (`JavaInteropTest` /
  `JvmJavaInteropCompilerTest#aHandleOfAFunctionGivenTheMarkersIsTheObjectItIs`),
  `JavaImplementationsTest#aHandleOfAFunctionEndsInTheMarkersOfACallMakingItsObject`,
  `JvmJavaInteropCompilerTest#javaStaticRefusesEverySiteThatNeedsReflection`,
  `#warnJavaReflectionReportsAnInterfaceImplementedByReflection` /
  `JavaInteropTest#warnOnReflectionReportsAnInterfaceImplementedByReflection`.
- A function costs `COST_PROXY` (8) against a functional interface
  (`JavaInterfaceMethods.isFunctionalInterface`), `COST_FUNCTION_OBJECT` (9) against `Object` (a
  fn is an `Object`; a functional interface is more specific), `COST_PROXY_NOT_FUNCTIONAL` (10)
  against any other: `TreeSet(Comparator)` beats `TreeSet(Collection)`, as a Java lambda's target
  does. Measured over java.base's public member groups (JDK 25.0.4):
  the `Object` row moves 32 choices from a non-functional interface to `Object`; 5 match the
  oracle (`StringBuilder.append(CharSequence)` etc.), the other 27 land on a generic method's
  erased `Object` form, a `ClassCastException` either way.

## Java arrays and Clojure typed arrays
- A Java array a `java:` form answers is the Clojure typed array it stands for: a rank-1 general
  array of the unmarshalled elements, stamped with the component type's Java name (a `byte[]`
  without `:octets` stays a list of signed bytes). Interpreter `LispArray.javaComponent`
  (`%array-java-component`), compiled header slot 4, bridge `arrayToVector`. Trap: the slot is
  shared with a remembered element type, whose names are RAW strings ("BIT"); the component is
  stored quote-framed ("\"int\""), which `_arrayElementType`/`_arrayDefaultElement` and the
  bridge's `javaComponentOf` read to tell the two apart (unframed, a runtime `:element-type`
  string answered `t` and a bit vector stopped being one).
- The stamp makes an argument cross as the array type the value is (`object-array` as `Object[]`,
  never the primitive array its element costs would choose). `arrayComponentCost` (direct sites
  `_jcomp`): exact for the same component, `WIDEN` for `Object[]` from a reference component,
  else `NO_MATCH` -- covariant arrays past `Object` are the deviation. A JVM program naming
  `java:call`/`java:static`/`java:field` forces the array runtime
  (`JvmLispCompiler.programAsksJavaForAnArray`).
- A Java method's writes into an array argument are written back when it returns (`writeBackArrays`
  on both run-time paths, direct sites `_jwb`...; fixed parameters only, a varargs tail is not). A
  copy, not a view: the oracle's identity is not kept. Pins: clojure-spec
  `a-java-array-answers-a-typed-array-and-crosses-back-as-its-component` (host-only),
  `JavaInteropTest`/`JvmJavaInteropCompilerTest` `arrayRoundTripsThroughCopyOf`.

## Handles and views: `java:handle`, `java:view`

Java objects standing for Lisp values, ONE implementation for both backends: the
`runtime/RontoJava*` classes (plain Java, importing nothing), shipped by a compiled program
(`JvmJavaImplementations.RUNTIME_CLASS_FILES` / `VIEW_RUNTIME_CLASS_FILES`:
`JvmLispCompiler.needsJavaValueRuntime` / `needsJavaViewRuntime`; the view arms exist only then,
`JvmJavaDirectSites.views`). A CL program of text-ordered handles ships no `_apply`, no
`$JavaCalls`.

- A `java:reify` given `:value` is a `RontoJavaValue` too ("Implementing interfaces").
- `RontoJavaValue`: every unmarshal answers `value()` (the bridge through `javaValueClass` /
  `javaValueMethod` bound BY NAME). One is a host object although its class is in `runtime`:
  `_jhost` and `isJavaObject` carve it out, in a program that makes one only.
- `(java:handle value text hash order class)` (`RontoJavaHandle`): `toString` the text, or nil ->
  `Object`'s `class@hex(hashCode)`. Equality: same class name and text, or with a NIL hash only an
  object standing for the very same value (`==`) under the same `className()`
  (`RontoJavaValue.sameValue`, so a function's object and a `:value` reify agree both ways), whose
  `hashCode` is `System.identityHashCode(value)`. `compareTo` by the order text or `(order value
  other')` (`RontoJavaCalls.order`); without an order the `ClassCastException` the oracle's
  non-Comparable class would throw, message included; another class's handle `class D cannot be
  cast to class C` first. A real number's handle is a `RontoJavaNumberHandle extends Number`
  (values computed by the maker, as Clojure's `Ratio`).
- `(java:view value items shape printer order class)`, shape `:list`/`:vector`/`:set`/`:map`:
  items converted ONCE as `Object` arguments (`VIEW_NO_VALUE`). `RontoJavaVectorView` adds
  `RandomAccess` and `Comparable`; set/map views over unmodifiable `LinkedHashSet` /
  `LinkedHashMap`; `equals` / `hashCode` the `java.util` contract's.
- As an ARGUMENT a set or map view is a host object of its class. A List view is KINDLESS
  (`_jkind` `KIND_VIEW` before `_jhost`): where its class fits, itself at a host object's cost;
  where an ARRAY is expected, an array of `items()` at `COST_VIEW_ARRAY` (12) plus their costs --
  after every way to pass it whole, varargs packing (10 + 1) included, so `Arrays.asList(view)`
  holds the view and `String.valueOf(view)` takes `Object`. Why a fallback: a Java array was
  unmarshalled into a list, so `(UUID/nameUUIDFromBytes (.getBytes s))` needs it. Why not the
  copy's own cost (`COST_BOXED` + elements'): it kept non-oracle choices (`String.valueOf` of a
  vector of characters `"ab"`, `List.of(v)` unpacking). Why not a fixed cost with the array at
  `COST_CONVERT`: `m(T...)` of one view would pack or unpack by its LENGTH (11 against 2 + 6n).
- `:bytes`: `(java:view value octets :bytes)` over an `(unsigned-byte 8)` vector is no collection
  and Java never holds it (`runtime/RontoJavaBytesView`, not a `RontoJavaValue`): wherever a
  `byte[]` fits (`JavaOverloads.bytesViewCost`: `byte[]` EXACT, `Object` / `Cloneable` /
  `Serializable` WIDEN) Java is handed the vector's own `byte[]` on every backend, so every Java
  store (a varargs tail, before a throw, later through `ByteBuffer.wrap`) is the vector's.
  Kindless (`KIND_BYTES` ahead of `_jhost`); a receiver as its `byte[]`. Pin:
  `JavaInteropPrograms.BYTES_VIEW_PROGRAM`.
- Callbacks (`RontoJavaCalls`: `text`, `order`): interpreter `JavaInterop.Calls`; compiled a
  generated `<Program>$JavaCalls` calling package-private `_jvtext` / `_jvorder`, which join
  `callbackNames()` (shaker roots, pinned to the main class on a split, the other-threads gate).
  Made only where `JvmJavaSites.callsBack`: `java:view`, or `java:handle` with an order that is no
  literal string or nil.
- Errors: `JavaImplementations.HANDLE_USAGE` / `VIEW_USAGE` + `, got X`, check for check on both;
  a wrong count a compile error compiled.
- NullAway: the runtime package is `@NullMarked` and cannot import `@Nullable`; its nulls are
  suppressed where they cross (`newHandle`, `newView`, `Calls.order`, `items()`/`get()`).
- Pins: `JavaInteropPrograms.JAVA_HANDLE_PROGRAM` and `JAVA_VIEW_PROGRAM` (both backends),
  `theBridgeAndADirectSiteAnswerAHandlesValueAlike`, `JvmRuntimeClassFilesTest`. The Clojure front
  end hands Java every value through them (`.kb/clojure-frontend.md`, "Java interop").

## Resolution: kinds, pure select, caches (both bridges, identical)
- A KIND is the smallest token every conversion cost is a pure function of: nil, t, `|false|`,
  integer, bignum, float, string of UTF-16 length 1 (never `char`: the oracle refuses one), other
  string, BMP char (never `int`), supplementary char (a fixnum), function value, host object = its
  exact `Class`; compared by identity. Conses, Lisp arrays and hash tables have NO kind (the cost
  sums the elements).
- `kindCost(kind, target)` is THE cost table; `marshal` = `kindCost` + `convert`, so cost and
  conversion cannot drift.
- `select(candidates, argc, cost)` reads no argument value: a pure function of candidates x kinds
  (what a compile-time resolver can call). A kindless argument passes `marshal` as the cost and is
  never remembered. When no overload takes it that way, a single candidate of the arity still
  takes it however it converts (`JavaOverloads.singleFixed`, `convertForced`), as the oracle's
  reflector calls one method directly.
- Caches: `ConcurrentHashMap` only (the template cannot subclass `ClassValue`); a map is cleared at
  4096 entries, a member keeps at most 16 memos (copy-on-write; a lost race only re-resolves).
- A new `marshal` rule is a new kind or a `kindCost` arm; the `remembered*` tests catch a kind
  that conflates two cost rows (integer/float).
- No `bench-report/` program: that suite compares portable ANSI CL across implementations, none
  of which has `java:`.

## One resolution model: sites resolved before they run (Clojure's, interpreter included)
- `compiler/JavaSiteResolver.resolve(site)` = a PURE function of the site form + a lookup:
  RESOLVED (static class + fully tagged designator) or unresolved with a reason. Static types
  (`JavaStaticType`): literal kinds; `java:new "C"` exactly C; a member's declared type
  (`ofDeclared`: a final class {C, nil}, Object/Number/CharSequence/arrays UNKNOWN, else
  `Bounded(C)`); `(the (java:object "C") x)`. An argument resolves the site only when EVERY kind it
  can have selects the same (executable, packed) (`COMBINATION_LIMIT` 256), so a resolved argument
  never changes the member.
- THE semantic difference (documented in the guide): a receiver typed by an upper bound resolves
  among the bound's methods; an overload only the run-time class adds is not a candidate
  (`Collection.remove(Object)` vs `ArrayList.remove(int)`). Only a site whose receiver class is
  unknown (or that the resolver cannot name) keeps the run-time class (Clojure's reflective
  fallback).
- A resolved site runs its member on both backends (interpreter `JavaInterop.invokeResolved`, JVM a
  direct call) with the same checks in the same order and the same texts: receiver not a java
  object, not an instance of the static class, then each argument whose run-time kind the
  resolution did not count on (`java:<op>: argument N is not <expected>, got X`). Only a false
  `the`/declaration delivers such a value; it is never converted for a member it was not chosen
  for. A member's throw is `error calling C.m: <throwable>` (etc.), a `java:java-exception` ("What
  a member throws") -- unless a callback raised it ("What a callback raises").
- Variable types: `compiler/JavaDeclarations` rewrites typed-variable references in java:
  receiver/argument positions into `(the <spec> v)` -- the ONE scope walk (macros expanded only to
  learn what they bind, never replaced: sites in expansions are rebuilt in the original tree by
  identity, `SourceProvenance.inherit` on every rebuilt cell). Sources: a body-head `(declare
  (type (java:object "C") v))` (wins over inference); let-initializer inference (Clojure's locals)
  unless the variable is special or ASSIGNED in its scope (`assignedIn` is conservative: a form it
  cannot see into assigns every candidate it mentions); `(declaim (type ...))` / `(proclaim '(type
  ...))` (quoted literal only) for every later site in PROGRAM ORDER where v is not rebound. A
  defvar's init types nothing (any form may setq it). One `JavaDeclarations` per program, fed every
  top-level form in order, so both paths see the same proclamations at every site.
- Spelling (`JavaSiteResolver.specOf`): Bounded(C) = `(java:object "C")`; {C} exact =
  `(java:object "C" :exact)`; a Lisp kind set = the smallest declared type covering it ("int"
  {integer}, "java.lang.Long" {integer,nil}, "void" {nil}, ...); FUNCTION/supplementary char: no
  spelling. Wider = fewer sites resolve, never another member.
- Known gaps (both paths agree unless noted): a site a user macro's expansion BUILDS is lowered on
  the compile path but not by the interpreter (it re-expands; the rewrite has nowhere to live) --
  visible only for an upper-bound receiver; a name a LATER form makes special is still inferred.
- Lookups: interpreter `ReflectiveJavaClasses` (Class.forName without init, through the program's
  Java class loader; canonical Type per Class via ClassValue). JVM compile
  `codegen.jvm.JvmClassFileLookup` over `am.ik.jvm.JvmClassPath`: a JDK's `lib/ct.sym` for one
  release (java.home, else JAVA_HOME, else `java` on PATH; works in the native CLI) +
  `--java-classpath`; a class newer than the running JDK is read with its version lowered in a
  copy. It re-implements `Class.getMethods()` (the `PublicMethods` merge; interface statics not
  inherited), `getMethod`, `getField` and `accessibleMethod`. Accessible = class-path class
  (unnamed module) or public + package exported to all (`module-info`). `JvmClassFileLookupTest`
  pins identical candidates, fields, subtyping and resolutions.
- ct.sym holds the JDK's OWN release, so one provider covers the default. Package-private
  supertypes of API classes are present (`AbstractStringBuilder`); impl classes are not
  (`ImmutableCollections$ListN`).
- Defaults: `--java-release` = the newest release in ct.sym (= what the interpreter on that JDK
  resolves against). No JDK found: class path only + one compile warning. A program without java:
  is byte-identical.
- Class version: a program with java: sites is stamped `max(61, 44 + R)`, R = the release resolved
  against (`JvmLispCompiler.classMajorVersion`, every `$PartN` too) -- javac's `--release` model:
  an older JRE refuses the class at load instead of `NoSuchMethodError` at the first call. Default
  17 rejected: it would resolve against a different API than the interpreter on the compiling JDK.
- Candidates: static AND instance for `java:call` (Java calls a static through an instance),
  STATIC ONLY for `java:static` (`JavaOverloads.staticMethods`, all three paths; its memo key is
  prefixed so the two never mix).
- An INTERFACE receiver's `java:call` candidates add `Object`'s public instance methods it does not
  redeclare (`JavaSiteResolver.callableMethods`; JLS 9.2 -- `invokeinterface List.getClass` links
  by JVMS 5.4.3.4). The lookups keep `getMethods()` semantics
  (`JvmClassFileLookupTest#anInterfaceReceiver...`), so reify slots are unaffected.
- `(java:field "C" "f")` of an instance field is `java:field: field C.f is not static` on all
  three; `(java:field obj "CONSTANT")` of a static field still reads it.
- A value after the field name WRITES it and answers the value (interpreter
  `JavaInterop.writeField`, bridge `writeField`). The value converts as the oracle's
  `Reflector.boxArg`: a number narrowed into a numeric primitive (`JavaOverloads.writtenNumber`;
  truncating, unchecked), `nil` there the reflector's NPE (`nullFieldWrite`), else `marshal`ed,
  refused as `unexpectedParamType` (IAE) for a numeric primitive, `castFailure` (CCE) otherwise. A
  final field is `Field.set`'s `IllegalAccessException` (the oracle's compiled `putstatic` gives
  `IllegalAccessError` -- deviation). A write is NEVER resolved (`JavaSiteResolver.FIELD_WRITE`),
  so `--java-static` refuses one.
- An instance field the class lacks is the oracle's `IllegalArgumentException` `No matching field
  found: f for class C` (`JavaOverloads.noMatchingField`).
- Warnings (`java:*warn-on-reflection*`, set by `--warn-java-reflection`): interpreter at top-level
  load for the sites the form shows (prefixed `file:line`) and at the first resolution of a site
  not shown there; compile path `JvmJavaSites.report` in source order, on from the flag or after a
  top-level `(setq java:*warn-on-reflection* t)`.

## Dispatch: static class, argument kinds read when it runs
- `JavaSiteResolver.select`: one member when every kind combination agrees; otherwise (an argument
  UNKNOWN or `Bounded`, too many combinations, combinations that differ or select nothing) a
  DISPATCHED site (`JavaSite.dispatched()`, `executable` null): `JavaOverloads.ranked` minus those
  no possible value matches (`viable`). Nothing viable, or an unlinkable parameter of a viable
  overload -> unresolved.
- THE choice: `JavaOverloads.selectRanked` = the first strictly cheapest; equals `select` whenever
  `beats` at equal cost is a strict total order (a non-transitive trio of covariant variants could
  differ -- none found: `JavaSiteResolverTest#theRankedOrderChoosesWhatSelectChooses`). No-match
  text = the oracle's refusal with the STATIC class.
- Interpreter: memo per site IDENTITY (`DISPATCHES`) -- what holds a many-overload site
  (`String.valueOf`) to the resolved path's cost.
- JVM (`JvmJavaDirectSites.SiteBuilder.emitDispatch`): one `_jcost$N(Object)I` per distinct
  (argument, parameter type), overloads scanned in rank order, one arm per overload. Helpers, once
  per attempt: `_jkind` (the bridge's `kindOf` order as codes), `_jseq`, `_jcost$N` (without the
  function arm the RETURNED variant: a function is never made a proxy on the way back),
  `_jconv$N` (the OPEN variant, for an argument that `mayBeFunction`, adds the FUNCTION arm -- the
  interface's generated proxy class -- and the sequence arm). The builders share a `Body`.
- No dispatched site needs the bridge: a possibly-function argument at an interface parameter
  becomes the interface's generated proxy class, so `String.join("-", xs)` with unknown `xs`
  compiles under `--java-static` (`JvmJavaSites.passesAFunction` predicts `_apply` from the same
  rule).
- With `--java-static`: swing.lisp, life-gui.lisp and java-interop.lisp leave only
  unknown-receiver sites to run time.

## Direct calls: resolved sites without reflection (JVM)
- A site resolves only through what bytecode can name (`JavaType.isLinkable` = primitive, or
  class-file `ACC_PUBLIC` (`isPublic`: a `protected` member class counts, as javac writes it) and
  accessible): the static class, each chosen parameter type, a host kind. `java:new` of an
  abstract class or interface is left to run time. The rule is in the shared resolver, so the
  interpreter leaves the same sites to run time and every resolved site CAN be a direct call --
  there is no "resolved but bridged" state. `JvmClassFileLookupTest#theTypesAgree` pins `isPublic`
  / `isAbstract` / `isLinkable`; `ReflectiveJavaClasses.isAccessible` reads the class-file flag
  (reflection disagrees for a protected member class).
- `JvmJavaDirectSites`: one `private static Object _jsite$N(Object...)` per site SHAPE (past 200
  values one `Object[]`). Receiver and arguments evaluate left to right (`--gpu` materializes
  each). Class resolution under a `NoClassDefFoundError` handler -> `No such class: C`; a
  `checkcast` after the join (the frame pass may merge arms to `Number`/`Object`); OWNER = the
  static class (javac's qualifying type: a method of a package-private superclass links through
  it); a Throwable handler -> the `error ...` text.
- Nothing in a direct call reflects: a FUNCTION for an interface parameter is the interface's
  generated proxy class (`JvmJavaImplementations.proxyFactory`). The bridge is emitted when
  `JvmJavaSites.needsBridge` (a pre-scan) or when forced: a site a pass rebuilt after the scan
  calls the absent `_javaInit`, `gateGroupFor` maps it to `GROUP_JAVA_BRIDGE`, and the attempt
  retries with the bridge. `usesEval` / the `_apply` root follow the bridge; `JavaPrint` follows
  `usesJava`.
- `--java-static` (`JvmLispCompiler.Builder.javaStatic`, JVM outputs only): no bridge ever; each
  site `JvmJavaSites.bridgeReason` names is refused at codegen, all listed with positions. The jar
  native-images with no metadata, output identical to `java -jar`
  (`ShippedBridgeNativeImageE2eTest#aJavaStaticJarRunsAsANativeImageWithNoConfiguration`).
- A direct site is at the loop floor (the JIT inlines it); the bridge pays for its dispatch (CHM
  gets, `String.hashCode` of the per-call name substrings, `Object[]` packing, `Method.invoke`).
- Known difference: the compile path folds some forms before the resolver sees them (`(code-char
  128512)` -> a literal), so such a site resolves compiled and not interpreted; same member.

## Implementing interfaces (java:reify, java:proxy, a function where an interface is expected)
- ONE rule, `compiler/JavaImplementations` -> `JavaImplementation` (its `Slot`s): the methods an
  implementing class DECLARES -- the interface's non-static `publicMethods()` grouped by
  `name(params)` in key order, variants by return type, so a compiled class is deterministic.
  `java:reify`: a designator must leave exactly one group of the interface or of Object's three,
  else the form's run-time error. An unnamed abstract variant (or two defaults) throws
  `UnsupportedOperationException("java:reify: no implementation of I.m(p)")`; a default keeps its
  body; Object's three are identity / `#<java-reify I>` unless named. `java:proxy`: every group but
  Object's three (defaults too) calls the callable with the name first.
- Dispatch key is `name(params)return` (`Slot.dispatchKey`): a covariant default variant is never
  shadowed by an abstract sibling.
- `(java:proxy "I" "J" ... callable)`: the groups are the union by key (`Consumer.accept(Object)`
  and `IntConsumer.accept(int)` two slots), all calling the one callable by name -- the Clojure
  proxy's per-name body. Interpreter: one `Proxy` defined in the first interface loader that sees
  every one (`proxyLoader`). Kind: `JavaImplementationType` per list; `single()` is null for
  several, so a call ON the object is left to run time (`--java-static` refuses it). A direct
  call's kind test adds `getClass().getInterfaces().length == n`, so a declared `(java:object "I"
  :exact)` holding a proxy of I and J is refused on the JVM as the interpreter's identity check
  refuses it. Pins: `JavaImplementationPrograms.PROXY_SEVERAL` / `_PASSED` /
  `FALSE_SINGLE_IMPLEMENTATION` in `JavaInteropTest` and `JvmJavaInteropCompilerTest`,
  `JavaImplementationsTest`, the parity test's `proxySlots(Class[])` corpus. A proxy over a class
  needs a generated subclass (no `Proxy` can): `java:subclass`.
- `(java:reify '("I" "J") [:value v] [:class "c"] "m" f ...)`: ONE object of
  `JavaImplementations.mostSpecific` of them (a listed interface another extends is dropped: the
  subinterface's `getMethods()` already holds the most specific default).
  `JavaInterfaceMethods.groups(list)` counts a declaration two interfaces inherit once
  (`Collection.stream()` through `List` and `Set` is no conflict; `List.spliterator()` vs
  `Set.spliterator()` still is). Messages name the interface DECLARING the method
  (`JavaImplementation.declaringName`). The options come BEFORE the pairs, so a value that is a
  marker keyword (`:value :java-false`) is the value.
- `:value` makes a STAND-IN (`JavaImplementation.StandIn`) implementing `runtime/RontoJavaValue`
  (`className()` the `:class` literal or its own class); `equals`/`hashCode`/`toString` no slot
  implements are the interface's static `sameValue` / `identityHash` / `identityText` -- ONE rule
  the handler, the generated class and the bridge (bound in `bind`) call. An interface declaring
  `value()` or `className()` refuses `:value` (`standInConflict`). A stand-in's `typeOf` is
  UNKNOWN (its sites dispatch by the object's class). Pins:
  `JavaImplementationPrograms.REIFY_SEVERAL_STANDING` (`JavaInteropTest` /
  `JvmJavaInteropCompilerTest#aReifyOfSeveralInterfacesMayStandForAValue`),
  `JavaImplementationsTest#aReifyOfSeveral...`, `#aReifyGivenAValueStandsForIt`,
  `theTemplateImplementsAnInterfaceAsTheSharedRuleDoes`. The Clojure front end's faces are its
  user (`.kb/clojure-frontend.md`, "Java faces").
- A value a function RETURNS is marshalled to the return type as an argument is, EXCEPT a function
  is never made a proxy (`marshal(..., proxies=false)`, returned `_jcost$N` / `_jconv$N`). Why: a
  return conversion to an interface would need that interface's proxy class, whose methods'
  interface returns need theirs -- a cascade of generated classes. Clojure's reify/proxy do not
  coerce return values either. Function -> interface stays for ARGUMENTS (Clojure 1.12's
  direction).
- `:functional` (a marker): a function argument converts by `JavaImplementations.functional`
  instead of `proxy`: a reify whose one function implements every group with an abstract variant,
  defaults keep their bodies. Costs do not depend on the marker, so overload choice and memos are
  the same; only the conversion differs (`shapeKey` carries the markers). `java:subclass` reads it
  for its constructor arguments. The Clojure lowering ends every host call with a non-literal
  argument in it (`ClojureInteropLowering.hostCall`). Pins: `JavaImplementationPrograms.FUNCTIONAL`
  (both backends), `JavaImplementationsTest#aFunctionalImplementation...`,
  `JavaBridgeTemplateParityTest#theTemplateImplementsAFunctionalArgument...`,
  `JavaSiteResolverTest#aTrailingFunctionalMarkerIsSetAside`.
- The object's KIND is `compiler/JavaImplementationType` (canonical per interface,
  `JavaClassLookup.implementationOf`): assignable to Object, `java.io.Serializable`, the interface
  and its superinterfaces -- a `Proxy` class's supertypes less `Proxy` -- so its cost depends on
  the interface alone and a call passing a literal `java:reify`/`java:proxy` RESOLVES. Spelled
  `(java:object "I" :exact)` (no object's class is exactly an interface), so a `let`-bound
  listener keeps it across add/remove calls. The interpreter maps its OWN Proxy objects to the
  kind (`JavaInterop.hostKind`). `java:reify` is a plain function on the interpreter (computed
  names work).
- JVM (`JvmJavaImplementations`): a form `JavaImplementations.resolve` resolves (literal names,
  interface found and linkable, every slot return type linkable) is `invokestatic
  <Program>$Reify<N>.of(Object[] functions)` / `$Proxy<N>`, one class per (interface, slots)
  shape, all extending ONE per-program `abstract <Program>$Implementation` -- what a direct call
  tests (`instanceof $Implementation && instanceof I`, as strict as the interpreter's kind check).
  A slot calls a PACKAGE-PRIVATE `_jimpl$K(Object fn, Object[] args)R`; the `_jimpl$` names are
  shaker roots and pinned to the main class on a split (`JvmLispCompiler.implementationCallbacks`).
  Anything else is the bridge's `javaReify` / `javaProxy` (`reifySlots` / `proxySlots`, a hand
  copy pinned by `JavaBridgeTemplateParityTest`); `--java-static` refuses it and the reflection
  warnings report it on both paths.
- NOT LambdaMetafactory: an `invokedynamic` implements only a functional interface's one abstract
  method and routes no default method -- java:proxy and an auto-proxied argument route every
  method to the callable, and a non-SAM interface (`MouseListener`) must work; one generator
  serves reify, proxy and arguments; plain classes need nothing from native-image nor `am.ik.jvm`
  indy support (BootstrapMethods, the shaker, the splitter).
- Known differences: the object prints as its class (`#<java Prog$Reify0>` compiled, a
  `jdk.proxyN.$ProxyM` interpreted); a parameter typed `java.lang.reflect.Proxy` accepts the
  interpreter's object only; a bridge-made object fails a direct call's `$Implementation` test,
  which only a false `(java:object "I" :exact)` can bring about.
- Native image with no configuration:
  `ShippedBridgeNativeImageE2eTest#anInterfaceImplementingJavaStaticJarRunsAsANativeImageWithNoConfiguration`.

## Extending a class (java:subclass)

- `(java:subclass "S" '(\"I\"...) '(\"m\"...) ctor-args... callable)`: one object extending `S`
  (and the interfaces), each named method calling `(callable this name args...)` -- `this` first,
  so a Clojure proxy body sees its object and `proxy-super` reaches the superclass implementation
  through the generated `super$m$a` accessor. The constructor arguments choose the superclass
  constructor by THE rule (public and protected constructors): at run time on the interpreter, by
  a generated `_jsubclass$N` compiled.
- One rule, `compiler/JavaImplementations.subclass` over `JavaType.overridableMethods()` and
  `subclassConstructors()`, pinned by `JvmClassFileLookupTest#everySuperclassIsSubclassedTheSame`.
  An unnamed abstract or interface method throws `UnsupportedOperationException` with the method's
  name (the oracle). `toString` / `equals` / `hashCode` override like any other method (unlike
  `java:proxy`).
- KIND: `JavaImplementationType` with a superclass; `single()` is null, so a call on it resolves
  by its class when it runs, while passing it where the superclass is expected resolves (a
  `WIDEN`). A direct call's kind test is `instanceof` the superclass (but not exactly it) and each
  interface, with exactly the extra interfaces -- as strict as the interpreter's identity check.
- Interpreter (`eval/ClassProxyMaker`): defined at run time (`java.lang.classfile`, one child loader
  per class, the dispatch interface `eval/ClassProxyHandler` typed with JDK types only).
  Interpreted native image stays refused.
- JVM: one `$Subclass<N>` per shape, overrides calling `_jsub$K(Object fn, Object self, Object[]
  args)`. A form left to run time is refused by name (the bridge's `javaSubclass` throws;
  `--java-static` at codegen) -- the interpreter resolves it when it runs, so computed names (and a
  project class the compile class path lacks) diverge by backend, documented in the guide.
- Tests: `testsupport/JavaImplementationPrograms.SUBCLASS` (both backends), `JavaInteropTest` /
  `JvmJavaInteropCompilerTest`, `JavaImplementationsTest`, `ClojureInteropTest` (`File`,
  `proxy-super`, `paintComponent`, snake and SAX shapes), `ClojureWasmInteropRefusalTest` (both
  wasm backends refuse with the undefined-function call-time error).

## The program's Java class path (`--java-classpath`, `--java-dep`)

Not the source path of a program in the Java subset (`--java-source-path`): that one is source
compiled into every output ([java-frontend.md](java-frontend.md), "A library from the source path").

- ONE carrier: `eval/SourceLoader.javaClassLoader()`. The evaluator derives its lookup from it
  (`ReflectiveJavaClasses.over(loader)`), and `JavaInterop.Caller.classes()` is what every
  name-to-class step uses; kinds and implementation types stay canonical per Class.
  `ClassProxyMaker` keys a generated subclass by the classes themselves (names collide across
  loaders). The Clojure lowering's reflection goes through `clojure/ClojureHostClasses.load`, bound
  per lowering from `ClojureFiles.javaClassLoader()` (a ThreadLocal: helpers deep in the lowering
  have no context). The compile path hands it in `CompileFrontend.Request.javaClassLoader`.
- CLI (`cli/JavaClassPath`): `--java-classpath` entries, then the `--java-dep` jars in Maven's
  runtime class path order (`MavenResolver.resolve`, `.kb/maven-resolver.md`), from Central then
  `--java-repository` in order. Central stays first because a mirror in `settings.xml` is the way
  to stop asking it; active `settings.xml` profiles' repositories go AHEAD of both
  (`.kb/maven-resolver.md` "Repositories"). `--java-update-snapshots` (Maven's `-U`) is
  `MavenResolver.Builder#updatePolicy("always")` on both resolvers (`JavaClassPath.mavenResolver`,
  `ClojureDepsRepositories.createDefault(true)`). Then the jars a Clojure program's `deps.edn`
  brings (`SourceLoader.addJavaClassPath`, `.kb/clojure-frontend.md` "deps.edn").
- The loader is a `URLClassLoader` subclass made once and ALWAYS (an empty one can grow:
  `addURL`) -- not in a native image (no run-time class definition: there the class path reaches
  only the class-file lookup, `JavaClassPath.classFiles`). A wasm compile's class path grows the
  same way: its lowering and macro time run on the JVM (`ClojureMacroTime.create(loader)`). Trap:
  growth must land before the first class question: `ReflectiveJavaClasses` remembers an absent
  name. `--java-release` / `--java-static` are compile-only; the class path is the interpreter's
  and the REPL's too.
- Outputs: a program jar copies each entry into `<stem>-lib/` and lists it in the manifest
  `Class-Path` -- exact class-path semantics for `java -jar` and `native-image -jar`, unlike
  bundling (services, signatures, multi-release, `getResources`). A `--no-main` library jar
  carries none: its pom lists the `--java-dep` coordinates. A war packs jars into `WEB-INF/lib/`,
  a directory into `WEB-INF/classes/`. A `.class` carries nothing (`java -cp`).
- Trap: `Class.forName(String)` is caller-sensitive, and the interpreter's caller is the program:
  `JavaInterop.invokeMethod` answers it through `Caller.classes().loader()`, where `Method.invoke`
  would ask rontolisp's own loader (a Clojure `instance?` of a dependency's class was false
  interpreted). Over rontolisp's own loader (native image) the reflective call stays. Pin:
  `ClojureProjectNamespacesTest#aDependencysPlainJavaClassesRunAsTheHostsOnTheInterpreterAndTheJvm`.
- Pins: `JavaInteropTest#aClassOnTheProgramsJavaClassPathIsReachable`,
  `JvmJavaInteropCompilerTest#aClassOnTheJavaClassPathIsCalledDirectly` (`testsupport/JavaLibraryJar`),
  `cli/JavaClassPathCliTest`.
- A class on the class path that cannot link (a Clojure library's class over `clojure.lang`:
  `.kb/clojure-frontend.md`, "Classes compiled against `clojure.lang`") refuses every call with
  `Cannot link class C: missing class M`, catchable on both: `eval/JavaInterop.unlinkable`, the
  bridge's (incl. its initializing `loadClass`), a direct call's `_junlinkable`. Only
  `NoClassDefFoundError` converts; a class the run-time class path lacks stays `No such class`.

## What a callback raises passes through the Java call
- A function called back from Java runs inside a Java call a `java:` site made. What LEAVES it
  thrown -- an exit (`return-from`, `throw`, `go`, `invoke-restart`), a condition, a raw failure,
  `uiop:quit`, the refusal of its value -- is recorded on its way out, and the site whose Java
  call throws THAT throwable throws it on unchanged; anything else is `error calling C.m:
  <throwable>`. Clojure's reify semantics. The `UnsupportedOperationException` of an
  unimplemented abstract method is the object's own Java failure: not recorded.
- Why a record, not a class test: the interpreter's signals are its own classes, but a compiled
  program's are JDK classes (`RuntimeException`, `ClassCastException`, ...) a Java method throws
  too. The boundary is the one place that knows a throwable is Lisp's.
- The record: per thread, newest first, at most `JavaImplementations.PENDING_SIGNALS` (16). A site
  that finds the throwable drops it and every NEWER entry (Java swallowed those on its way out:
  `Stream.close` relays the first handler's throwable with the rest suppressed, so a single slot
  would lose the first). What Java wraps (`FutureTask.get`'s `ExecutionException`) or rethrows on
  another thread is a Java failure like any other.
- Interpreter: `JavaInterop.ImplementationHandler.invoke` -> `raised`; `fail` -> `passedOn`.
- Compiled: `_jsig(Throwable)Throwable` handles every `_jimpl$K` and the bridge's Proxy; every
  site's failure handler calls `_jfail(Throwable,String)Throwable` (one method holds the rule; the
  bridge's `fail` calls it too). The record `_jsigTl` exists only when a callback can.
  `JvmJavaDirectSites.finishHelpers` builds both after every body (what they read depends on the
  program's channels); `_jsig`/`_jfail` are `REFLECTIVELY_FOUND_METHODS` and, with the bridge,
  shaker roots.
- Compiled custody: `_jsig` takes the throwable's own condition off `_condTl` (keyed by the
  throwable: [error-handling.md](error-handling.md), "The JVM keeps what a throwable carries under
  the throwable") and its own entry off the `_nleTl` exit stack; `_jfail` puts both back for the
  throwable it passes on. Without it the `Stream.close` relay answered the SECOND handler's
  condition, and a condition `FutureTask.run` swallowed was read by the next `handler-case` (the
  last `CALLBACK_SIGNALS` row pins the keyed `_condTl`).
- Pins: `JavaInteropTest` / `JvmJavaInteropCompilerTest#whatACallbackRaisesPassesThroughTheJavaCall`
  over `testsupport/JavaImplementationPrograms.CALLBACK_SIGNALS`,
  `JvmLispCompilerSplitTest#aForcedSplitKeepsJavaCallsWorking`, `ShippedBridgeNativeImageE2eTest`
  (the agent-config program binds `_jsig`/`_jfail`); user doc `guides/java-interop.md`, "Errors
  and non-local exits".

## What a member throws: `java:java-exception`

**A failed call signals `java:java-exception`, a `simple-error` (report `error calling C.m:
<throwable>`) whose slot 2 (`%JAVA-EXCEPTION-CAUSE`) holds the throwable;
`java:java-exception-cause` (prelude) answers it.** Handed back to a member (an argument of a
dispatched or run-time site, the receiver of a run-time `java:call`) one is that throwable.

- The class is seeded on demand (`ClosRegistry.ensureJavaExceptionSeeded`, never in
  `CONDITION_SEEDS`: a registered class joins every `error` typep table), so a wasm program stays
  byte-identical.
- JVM: `_jfail` records the throwable under its `RuntimeException` in `_jexMap`, a synchronized
  `WeakHashMap` (the record follows the exception across an await or join); `_hcSynth`'s first arm
  builds the instance. Not the exception's cause: the default handler would print the cause's
  frames under the one-line uncaught report.
- Conversion back (`JavaInterop.hostException`; JVM `_jexc`, only where `routesConditionReports`
  and the class is registered): slot 2 holds the throwable, or a function building it called with
  the condition -- the protocol of the Clojure runtime's exceptions (`.kb/clojure-frontend.md`,
  "Host exceptions").
- Pins: `JavaInteropTest` / `JvmJavaInteropCompilerTest#aFailedCallSignalsAJavaExceptionCarryingWhatTheMemberThrew`
  over `testsupport/JavaInteropPrograms.HOST_EXCEPTION_PROGRAM`,
  `ClojureProjectNamespacesTest#aClojureExceptionCaughtInCommonLispCarriesItsHostException`.

## Tests / docs
`JavaSiteResolverTest`, `JavaDeclarationsTest`, `JavaImplementationsTest` (compiler),
`JvmRuntimeClassFilesTest`, `JvmClassFileLookupTest` (incl. `everyInterfaceIsImplementedTheSame`),
`JavaBridgeTemplateParityTest`, `am.ik.jvm.JvmClassPathTest`; direct calls:
`JvmJavaInteropCompilerTest` (javap shape, class version, `--java-static`, `aDispatchedSite*`;
programs shared with `JavaInteropTest` through `testsupport/JavaInteropPrograms` and
`testsupport/JavaImplementationPrograms`), `JvmLispCompilerSplitTest#aForcedSplitKeepsJavaCallsWorking`,
`JvmDeadMethodEliminationTest#keepsTheCallbacksOfAGeneratedInterfaceImplementation`,
`ShippedBridgeNativeImageE2eTest`. `JavaInteropTest` + `JvmJavaInteropCompilerTest` mirror the
same cases — keep in step, headless only. `examples/jvm/{java-interop,swing,life-gui}.lisp`;
`doc/{en,ja}/guides/java-interop.md` + the `reference/functions/java-*.md` pages (a GUI form hangs
`DocExamplesTest`).

## Host coercions match the oracle's reflector
- Measured against clj 1.12.6 (`clojure.lang.Reflector#paramArgTypeMatch`): a fixnum matches
  `long` exactly and `int` by checked narrowing (`Math.toIntExact`: `(Integer/toHexString
  4294967295)` is the oracle's `ArithmeticException: integer overflow`), a `Long`, or a supertype
  of `Long`/`Integer`; no floating type (`(Double/valueOf 5)` refuses), no `char`, no
  `BigInteger`. A float matches `double` alone; `float` only where no `double` takes it (NARROW). A
  one-character string matches no `char`; a character no `int`; `nil` no primitive, not even
  `boolean`. Host boxes keep pure assignability (a host `Byte` never occurs: `unmarshal` answers a
  fixnum).
- A SINGLE fixed-arity candidate of its arity (`JavaOverloads.singleFixed`; a varargs executable
  whose packed shape also applies keeps the dispatch) skips the table on all three paths
  (resolver `resolveToOpen`, interpreter, bridge): the oracle's compiler calls one method
  directly. Arguments convert with the oracle's checked casts (`convertChecked*`, direct sites
  `_jchk$N`), a failure the `ClassCastException: class FROM cannot be cast to class TO` without
  the module tail, FROM the Lisp class (`LispRatio`, `LispSymbol`, ...; direct sites name it through
  `_jcls`, since compiled values are `BigInteger[]`, bare `String`, `Object[]`). So `(Math/sqrt
  16)` is `4.0`, `(Integer/toHexString 1.5)` `"1"`.
- One refusal is NOT a `java:java-exception`: the function-where-`Object`-is-expected refusal is a
  PLAIN error on the interpreter (thrown past the `fail` wrapping), so a direct site refuses it
  BEFORE its conversion handler (`emitFunctionObjectRefusals`); a function nested in a sequence is
  refused by the checked conversion through `_jsig`, so the handler throws it on unchanged. Pin:
  `JavaImplementationPrograms.FUNCTION_OBJECT` row 4.
- Tagged designators (`put(byte)`) resolve explicitly and convert UNCHECKED.
- Argument hints: a designator whose entries are `^C` or `_` (`submit(^java.lang.Runnable,_)`) is
  no tag (`JavaOverloads.Member.hints`). `JavaOverloads.filterByHints` (the resolver only) keeps
  candidates whose hinted reference parameters take the hint's class, all when none is kept. The
  run-time paths read no hint: Clojure's reflector reads none. The Clojure front end spells its
  arguments' `:tag`s this way (`.kb/clojure-frontend.md`, "Java interop").
- A function costs `COST_FUNCTION_CALLABLE` (7) for `java.util.concurrent.Callable`, below every
  other interface: `ExecutorService.submit` of a function is `submit(Callable)` and its future
  keeps the answer. The oracle picks `Callable` for `ThreadPoolExecutor`, `ForkJoinPool`,
  `ExecutorCompletionService` and the virtual-thread executor, `Runnable` for
  `ScheduledThreadPoolExecutor.submit` (HotSpot `getMethods()` order, not reproducible).
- Test note: `(byte x)` answers a fixnum here (the oracle's `Byte`), which the reflector refuses
  for `byte` parameters -- so `ClojureInteropTest#anArrayJavaKeepsIsTheByteArrayItself` writes
  through `putChar`.
- Pins: `JavaSiteResolverTest` (`max(long,long)`, the `append` reference set, single-candidate
  rows), `JavaInteropTest`/`JvmJavaInteropCompilerTest` (`characterRefusesAnIntParameter`,
  `FUNCTION_OBJECT` row 3, `HOST_EXCEPTION_PROGRAM`'s `LispInstance`),
  `JavaBridgeTemplateParityTest` (`singleFixed` fallback); guide
  `doc/{en,ja}/guides/java-interop.md` ("Overload resolution") + `java-static.md`.
