# The EXPERIMENTAL front end for a subset of the Java language (`am.ik.rontolisp.java`)

**Invariant: a Java file is read, typed and LOWERED to the Common Lisp core forms the pipeline
already consumes; no backend learns a Java name; a construct the subset does not have is
refused BY NAME at its position, never lowered as something else.** Status is experimental -- "an
experimental subset of the Java language", no compatibility claim -- and every user surface says
so (`--source-language` help, `doc/*/java/`, `examples/java/README.md`). Provenance and naming
rules: [java-frontend-provenance.md](java-frontend-provenance.md). `--no-gc` is refused by name
(`CompileFrontend.run`). First slice: j68 (2026-11-17); the declarations and statements past
it: j69 (2026-11-17); type parameters, inference, lambdas and boxes: j70 (2026-11-17); the
library: j71 (2026-11-17); the numbers: j72 (2026-11-17); the exceptions: j73 (2026-11-17);
several files, a session, the calls across languages, the tools and annotations: j74
(2026-11-17); threads: j76 (2026-11-17); the flow, outer instances and qualified `super`: j77
(2026-11-17); inner class types, intersections, the inference j70 left: j78 (2026-11-17);
`java.io`, the charsets and the members j71 left: j79 (2026-11-17); the class of an array and
the `Math` members j72 left: j81 (2026-11-17); the uncaught report without a held condition
and the catch that signals nothing again: j82 (2026-11-17); the classes a JDK synchronizes, a
class's own initialization, `java.util.concurrent` and the two leads for a suspended thread
measured: j83 (2026-11-17); the unchecked warnings, a lambda cast to an intersection,
`Type::<T>new` and an argument call among overloads: j86 (2026-11-17); regular expressions
on the engine shared with the Clojure front end: j90 (2026-11-18); the rest of `java.io`,
`Appendable`, `Locale`, a `Matcher`'s region, `hitEnd` and `appendReplacement`, and the
line a `BufferedReader` takes whole from the host: j87 (2026-11-18); `charAt` as one read,
a static initialization that throws, an uncaught exception with its causes and what a
program that throws carries: j88 (2026-11-18); a task at its time (a scheduled pool, a
`Timer`, the thread one stack starts for a time), the rest of `java.util.concurrent`, the
members a class does not inherit, `super` to `Object`'s methods and the analysis of the
`synchronized` regions a suspended thread can hold: j89 (2026-11-18); a thread suspended
and resumed on wasm (`JavaResumable`), which took the one-stack thread away: k00
(2026-11-18); the members a probe
of everyday code met refused, `Character` over the Unicode tables, a class compared with
a class, and the UTF-16 charsets paid for only where named: j98 (2026-11-18); checked
exceptions, the catch clause nothing reaches, and an exit a cleanup takes on the JVM and
wasm: j99 (2026-11-18); a stamped lock, a phaser, a transfer queue, the completers and
accumulators, a pool's hooks and sizes, a structure named by its class's binary name, a
field hiding one above it and `super.x`: k01 (2026-11-18); the HTTP client
(`java.net.http` over the program's own `rontolisp:fetch`) and `java.net.URI`: k03
(2026-11-18); the HTTP server (`com.sun.net.httpserver` over the program's own
`%http-serve-begin`, a wasi:http component under `--component`): k04 (2026-11-19); the
time limits of the HTTP client over fetch's two timeouts, `java.time.Duration` and the
members j98 left refused that a line of Java answers: k05 (2026-11-19); the flow's errors
of a class and the classes in it in the order a compiler reports them: k06 (2026-11-19);
the host's events giving a component's threads their turns, so that a served request
that waits leaves its instance to the host, a loop's turn outside its resume body and
`Thread.State.BLOCKED`: k07 (2026-11-19); a class of Java source under `Number` (the
atomics, a program's own), the field updaters, `invokeAll` with a limit and
`join(Duration)`: k08 (2026-11-19); a program that starts threads run compiled in the
browser, whose interpreter has one stack: k10 (2026-11-19); `java.util.stream`, the
collectors and the inference they need, `Name.class::isInstance` / `::cast`: k12
(2026-11-19); a library read as source from the program's source path and lowered as far
as the program reaches it, annotation types and type annotations, an exact method
reference in a call's applicability: k24 (2026-11-19); what comes after is `.todo/k15`,
`k16`, `k17`, `k18`, `k19`, `k20`, `k21`, `k22`, `k82` and `k85` .. `k91` (what k00, k11, k06, k07,
k08, k10, k12, k26, k47, k55, k60, k71 and k72 left); YAVI compiled to wasm in both of its styles, the milestone k24 was
asked for, and where its bytes go: k33 (2026-11-20; "YAVI compiled to wasm" below); the
Maven plugin's `wasm` goal reading a project's
dependencies from their sources jars, a session reading the source path: k32 (2026-11-20);
a library's class in the file of another, the playground's uploads as a program's source
tree, a sources jar's resources measured: k44 (2026-11-20); the resources a program and its
libraries read: k53 (2026-11-20; "Resources" below); a resource name computed where the program runs,
`Properties`, `ResourceBundle` and `ServiceLoader`: k58 (2026-11-20; "Resources" below);
`java.net.URL`, a resource's URL, the loaders above the program's, `ServiceLoader` of a computed
class, a member class as a bundle: k69 (2026-11-20; "Resources" below; `.todo/k75` is what it
left); `LinkedHashMap`, `LinkedHashSet`, `LinkedList`: k27 (2026-11-19);
`java.text.MessageFormat`: k28 (2026-11-19); `java.text.Normalizer` over the normalization
forms the front ends share: k29 (2026-11-19); a `Class` as a value, a type variable in
scope kept apart from the one a call finds, `Name.this` as the instance of `Name`
itself: k30 (2026-11-20; `.todo/k39`, `k40` and `k41` are what it found beside its way);
`java.util.UUID`: k36 (2026-11-20); `java.math` (`BigInteger`, `BigDecimal`,
`MathContext`, `RoundingMode`): k31 (2026-11-20); `java.time` (the dates, times and
instants read from their texts, the fields and units, the zones of one offset, a clock):
k35 (2026-11-20; the regions of the tz database are k42's: "java.time: the regions");
the formatter and the queries of `java.time`: k43 (2026-11-20; `.todo/k52` is what it
left: "java.time: the formatter"); `java.util.Date` and `java.sql`'s
`Date`, `Time`, `Timestamp`, `import module java.sql`, the two levels of imports on demand:
k37 (2026-11-20); the stream members k12 left (`mapMulti`, the gatherers, a stream's and
the numbers' spliterators, the streams of a `Random`, the collectors of a concurrent
map), a stream inside `flatMap` closed where its stream stops asking, and
`String.format` of a `BigInteger` and a `BigDecimal`: k26 (2026-11-20); YAVI's reader of an
untyped input split by the type asked for, sent to YAVI as a pull request and measured
over its sources: k46 (2026-11-20; "YAVI compiled to wasm" below; the examples' release is
`.todo/k50`); `TreeMap`, `TreeSet`, the sorted and navigable interfaces and their
views, the wrappers of them, `ConcurrentSkipListMap` / `Set`, `Map.remove` and `replace` of a
value, `EnumSet` and `EnumMap`, `Random`'s bytes, floats and draws between bounds: k47
(2026-11-20; "The sorted collections", "The enum collections" below); the sequenced
collections and their reversed views, with what their probes found in the collections
beside them: k55 (2026-11-20; "The sequenced collections" below); the collections of no
element, of one and of n copies of `Collections`, `singletonMap`, the refusals of
`List.of`'s list, a bridge's cast and `PriorityQueue`: k60 (2026-11-20; "The collections of
Collections, a bridge's cast and PriorityQueue" below); `StringJoiner`, `StringTokenizer`,
`BitSet`, `Scanner`, `Readable` and `CharBuffer`, hexadecimal floats in a parse: k72
(2026-11-21; "A joiner, a tokenizer, a bit set, a scanner and a char buffer" below).

## Where it sits

- `java` depends on the AST types and `reader` (`LispReadException`) only (`PackageCycleTest`):
  `JavaLexer` + `JavaParser` + `JavaSyntax` (text -> tree; own lexer and parser, no dependency),
  `JavaClasses` (the class table of one program), `JavaClass` / `JavaType` (members, static
  types), `JavaTypes` (the relations between types: substitution, subtyping, capture,
  inference), `JavaIntrinsics` (the library classes whose values are Lisp values), `JavaLowering`
  (tree -> core forms, typing as it goes), `JavaFlow` (what the flow of a class's bodies
  decides: reachability, definite assignment, effective finality), `JavaInitOrder` (when a
  class's static initialization runs), `JavaPatterns` (what a switch's patterns cover),
  `JavaNames` (spelling), `JavaForms`, `Java` (facade).
- Reached ONLY through the seam: `eval/SourceLanguage.JAVA`, `.java` or `--source-language java`
  ([source-language.md](source-language.md)). A program is the file it starts at and the files
  of its source tree it names, lowered whole together ("A program of several files" below). A
  session (`JavaSession`, the REPL's `java> ` and the playground's pick) lowers every buffer with
  the declarations of the earlier ones ("A session" below).
- The run-time half is `eval/java.lisp`, spliced by `eval/JavaLibrary` the `StarlarkLibrary` way:
  the interpreter loads it on the first resolution of a `rontolisp::%java-` FUNCTION,
  `CompileFrontend.expand` splices it inside `StarlarkLibrary.process`, `LibraryDefunPruner`
  drops what stays unreachable. Name gate: a member starting `%JAVA-`.
- **Library classes are of two kinds** ("The library" below). One whose representation is a
  Lisp value, or that holds no value, is an INTRINSIC (`JavaIntrinsics`): its members are
  emitters of forms, resolved by the same overload resolution as a program's methods. Any other
  is JAVA SOURCE in the jar (`src/main/resources/am/ik/rontolisp/java/library/`, one class a
  file, found by qualified name), parsed with the program naming it and lowered as far as the
  program reaches it. `resource-config.json` carries both for the native image.

## The decisions (2026-11-17)

- **Static types pick the form.** Every expression lowers to `(form, type, constant)`
  (`JavaLowering.Typed`); a conversion (widening, boxing, a number's text) is made where the
  types say, never tested at run time.
- **`null` is `NIL`; `boolean` is `T`/`NIL`; no box is `null`.** A boxed number is the number, a
  boxed `char` a Lisp CHARACTER (so `Object o = 'c'` prints `c`), a boxed `true` `T`, a boxed
  `false` `:false` -- so `Map<String, Boolean>.get` tells a stored false from a missing key.
  Rejected: `null` as `:null` (every reference test and every slot default pays, where only a
  `Boolean` box does now). A boxed `long`, `byte`, `short` and `float` is the cons
  `(value . tag)` ("Boxes" below).
- **`int` is an integer kept in range** by `(%mask-signed-field 32 x)`, `short` and `byte`
  the same at 16 and 8 bits, `long` at 64 (the unconditional mask
  `(- (logand (+ x 2^31) (2^32 - 1)) 2^31)` until k56); `char` an integer whose static type
  prints it, a `float` the double of its value. A variable of an integral type is declared of
  its range, an operand the form does not bound asserted with `the` ("Numbers" below).
- **A class is a `defstruct`** named by the class's BINARY name (`Outer$Inner`,
  `java.lang.ThreadLocal`; k01, `JavaNames.structure`: the structure's name is the one
  thing of a class the run time reads, for `Object.toString()`, and every other Lisp name
  of a class stays its `lispName`), `extends` its `:include`, a field a slot defaulting by
  type -- a field named as one of a class above it a slot `name%n`, `n` the fields of the
  name above (`JavaClass.Field.slot`, k01: a structure has one slot of a name and holds
  the slots above it; the static type a field is read through picks the accessor, so
  `super.x` is `this` typed as the class above, `JavaSyntax.Super`, and a private field
  above is not even hidden; refused by name until then) --, a
  static field a `defvar` global set before `main`, an instance method
  `(defun |C.m(int,String)| (this ..))`. A constructor is two functions: `|C.<init>(..)|` on a
  made instance (explicit `this(..)`/`super(..)` or the implicit `super()`, then the field
  initializers unless it delegated, then the body) and `|C.new(..)|` making one. A constructor
  that does nothing (no parameter, no statement, no initialized field, the same above) is
  never called.
- **Names** (`JavaNames`): verbatim when the name holds an ASCII lowercase letter, else `j%` +
  the name, so `T`, `NIL`, `LIST`, a class `A` never meet the pipeline's. A class's Lisp name is
  its qualified name, a `java.lang` class its simple one; a program's class named as a
  `java.lang` class the program also uses is refused. An accessor is `Class.` + the slot.
  Generated: `%t<n>`, `%fn`, `%break`, `%continue`, `%string`, `%equals`, `%hash`, `%class-name`,
  `%simple-name`, `%lisp-error`, `%catch<n>`, `%uncaught`, `Class.m(sig)%v`, `Iface%is`.
- **The program is closed, so a call is direct when it can be.** One body under the receiver's
  static type: a plain call. Several: one `cond` over the structure predicates, deepest owner
  first, made once per (static type, signature), ending in `%java-no-receiver`. A receiver that
  can be a lambda is tested by `functionp` first; one that can only be a lambda is `funcall`
  ("Lambdas" below). `super.m()` and a `private` method are direct.
- **Overloads**: the candidates the arguments fit with no boxing, else with it, else as the
  elements of a variable arity parameter; among them the one whose parameters convert to every
  other's; none such is `reference to .. is ambiguous`. A lambda and a method reference wait
  for the chosen parameter's type ("Inference" below).
- **Constants** are folded as the language computes them (`fold`), so a constant variable
  (`static final`, primitive or `String`, constant initializer) is its value at every read --
  whatever the order of the classes -- and `char c = 'a' + 1` is a `char`.
- **Static initialization: at first use where it shows, before `main` where it cannot**
  ("Static initialization" below).
- **An exception is one condition type holding the object** (`%java-throwable`), `throw` is
  `(%java-throw obj #'describe)`, `describe` the program's `Throwable.toString()` target, which
  the condition's `:report` calls. `try`: a `handler-case` clause per `catch`, its type
  `(satisfies %catch<n>)` -- the generated test of the thrown object's class -- and its
  variable bound to `(%java-thrown c)`; a condition no clause takes goes on as it came;
  `finally` is `unwind-protect`. What the run time raises is converted AT THE CATCH
  ("Exceptions" below).
- **The program runs inside `(%reporting-uncaught #'%java-uncaught-line (%host-exit 1) (%initialization) (main))`**:
  what nothing caught is a JDK's line, the pipeline's location lines under it and the status 1
  on four backends, with no handler holding the condition ("Exceptions" below).
  `%initialization` is there only where a class is initialized before `main` ("Static
  initialization" below).
- **`instanceof` / a cast**: a class is its predicate; an interface the generated `Iface%is`
  (the predicates of the topmost classes implementing it, of the library's values that are it,
  and of its lambdas: "Lambdas" below); a library class its own (`simple-string-p`, `integerp`,
  `consp` for a `Long`, `%java-number-p`, `%java-sb-p`). A cast to a type variable
  tests its bound, to a parameterized type its class; a cast only the type arguments tell from
  no cast has no test. `instanceof` of a type the erased program cannot answer is the
  compiler's `X cannot be safely cast to Y`. A failing cast throws `ClassCastException()`.
  A narrowing cast or `instanceof` from a non-array type to `int[]`, `byte[]`, `float[]`,
  `double[]`, `boolean[]` is `typep` of the array's element type ("Numbers" below); to any
  other array type, or from an array type, it is `%java-array-is` over the class a widening
  recorded ("The class of an array" below). The store check is not made (measured there).
- **`Object`'s methods**: a class declaring one is called as any method; through a type none of
  whose classes declares it, the program-wide `%string` / `%equals` / `%hash` test the classes
  declaring one, deepest first, then `%java-to-string` / `-equals` / `-hash-code` answer for
  every other value (identity for a structure: a number kept in an `eq` table from the first
  ask). **A class compared with a class, or asked its name, is its name** (j98):
  `getClass()` and a class literal (`Name.class`, `JavaSyntax.ClassLiteral`) where one is
  compared with the other (`==`, `!=`, `.equals`: `equal` of the two binary names,
  `JavaLowering.sameClass`) and before `.getName()` / `.getSimpleName()` make no object;
  anywhere else each is the class object ("A class as a value" below, k30; refused by
  name until then). That is the `equals` an IDE writes (`getClass() != o.getClass()`),
  the one use a probe of everyday code met. With `==` the compiler's `incomparable types:
  Class<..> and Class<..>` is kept as javac gave it over 16 shapes (two literals of two
  classes, a literal of no class under the object's type, two objects of unrelated
  classes), but one: an interface beside a `final` class that does not implement it is
  compared. A method reference `Name.class::isInstance` / `Name.class::cast` is the test
  and the cast of its one argument (k12, `referenced`: the literal is never lowered).
  `super.toString()`,
  `super.equals(o)` and `super.hashCode()` with no class above declaring the method are
  those three run-time functions on `this` (j89; refused by name until then, and any other
  member of `Object` through `super` still is). The identity text names a structure by its
  own name, which is the class's binary name since k01 (until then the Lisp name:
  `Outer.Inner@1f`, `ThreadLocal@1f`). Cost: the longer names of the made classes of
  `java.lang` (a counter under four threads 109,069 -> 109,253 B of wasm), nothing to a
  program that makes none.
- **A class of Java source stands under `Number`, which stays an intrinsic** (k08,
  2026-11-19; until then `a class extending java.lang.Number` was refused and an atomic
  was no `Number`). `Number` is the one abstract intrinsic class; a class extending it
  keeps `superclass == null` and names it in `JavaClass.intrinsicSuperclass`, which only
  the TYPE relations read (`isSubclassOf`, the three `supertypes` walks, `asSuper`, an
  array's recorded supertypes) -- so no structure is included, no constructor called,
  no body inherited, and the 60 other walks of `superclass` are as they were. Rejected:
  `superclass` set to the intrinsic (each of those walks would have to learn that a
  class above can have no structure), and `Number` as Java source with the boxes under
  it (a box is a Lisp value: every `instanceof` and every member through `Number` would
  pay in a program with no such class). What the lowering does, all of it only where
  the program can hold an instance of such a class (`isExtended`: a made class under an
  abstract intrinsic; a program with none is byte-identical, measured below):
  `Number`'s four abstract members are abstract methods holding the boxes' form
  (`Builder.required`), and a call of one through `Number` is a dispatcher
  `|Number.intValue()%v|` over the bodies of the made classes, deepest first, whose last
  arm is that form (`intrinsicCall`); `byteValue` / `shortValue` narrow what the
  `intValue` dispatcher answers (`Context.member`), are a dispatcher of their own only
  where a class overrides one, and through `super` are the form on `this`; `instanceof`,
  a cast and a pattern ask `|Number%is|` (the intrinsic's predicate and the topmost made
  classes', made by `interfaceTest`); `Number` stops being plain (`isPlain`), so
  `toString` / `equals` / `hashCode` and a concatenation through it go to the program's
  `%string` / `%equals` / `%hash`. `new Number() { .. }` is an anonymous class of it. A
  class leaving one of the four out is the compiler's `.. does not override abstract
  method doubleValue() in Number`. `AtomicInteger`, `AtomicLong`, `LongAdder`,
  `DoubleAdder`, `LongAccumulator` and `DoubleAccumulator` extend it in the library's
  source. Measured (class / wasm bytes, `sizes/` of the k08 artefacts, the base jar
  against this one): `Plain` (boxes through `Number`) 151,684 / 126,407 and `Count` (an
  `AtomicInteger` under four threads) 185,612 / 164,198 unchanged, as k01's `Exec`, `Cf`,
  `Sched1` and `examples/java/shop`; `Under` (`Plain` with one class of its own) 161,555 /
  132,331; `CountNumber` (`Count` reading its atomic through `Number`) 186,917 /
  165,166. Pinned by the `standalone:` case `a-class-of-java-source-stands-under-number`
  (a file of its own, so that the corpus program keeps the plain lowering),
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (both
  lowerings of one source) and the `refused:` case
  `a-class-under-number-declares-the-members-it-leaves-abstract`.
- **The abstract method a class leaves out is named as javac names it** (k08, asked over
  seven shapes): of the first type that has one -- the class's superclass chain ahead of
  its interfaces, the walk `supertypes` makes -- the one declared LAST
  (`implementations` reads `methods.reversed()`); `super.m()` of an abstract method says
  `in <class>`. Both were wrong before (`a()` named where javac names `c()`).
- **An intrinsic class counts in a dispatcher and an interface test only where the
  program can hold its values** (k12, `JavaClass.held`, `JavaClasses.held`): set where
  `qualified` answers the class outside the declaration of a library class -- a
  declaration of the program, a body being lowered, a boxing. Until then a box was in
  `Comparable%is` and `Comparable.compareTo(Object)%v` as soon as it was in the table,
  and a library class's member types put it there: `Collection.stream()` names
  `Stream`, whose `mapToDouble` names `DoubleStream`, which names `Double`, so every
  program with a collection would have carried a `FLOATP` arm and
  `%java-double-compare` (measured: `examples/java/shop` 328,194 -> 328,869 B of wasm
  before the flag, 328,252 with it, the rest being `compareTo`'s null test below).
- **A simple name finds what a class HAS, not what a class above it declares** (j89;
  `JavaLowering.inherits`, JLS 8.2): a `private` member, and from another package one that
  is neither `public` nor `protected`, is no member of the classes under its class, so the
  name goes on to the classes and the method around (`variable`, the unqualified call's
  `inherited`). Until then `String name = ..; new Thread() { public void run() { use(name); } }`
  was `name has private access in Thread`, and a library class's field named as a
  program's local took the local's place in every anonymous subclass. With nothing else of
  the name a field is the compiler's `x has private access in A` / `is not public in A;
  cannot be accessed from outside package`, a method `cannot find symbol` (asked of javac:
  the corpus's two `refused:` cases). A qualified access (`o.x`, `super.m()`) is as before.
- **The string model: a string's elements are code points** (the host CHARACTER); `hashCode` is
  the specified number over the UTF-16 form. The host's `princ` of a double IS the language's
  text but for the exponent letter (measured 2026-11-17: 628 doubles, four backends).
- **Where a program starts** (measured against a JDK 25 source launcher): the first type of the
  file when it has a `main`, else the class named as the file and no other; `main(String[])`
  before `main()`, declared or inherited, not private; an instance `main` runs on an instance of
  the class DECLARING it. The arguments are an empty array.
- **Lowered until the class table is still**: a library class is read where a body first names
  it, which can be after a call whose dispatch it changes, so `JavaLowering.lower` lowers again
  from the start when a pass moved the table's version: a class added (a library class, a local
  or an anonymous class, an anonymous class's constructor, an enum's `values()`), a local found
  to be read by a local class, the set of lazily initialized classes.

## A program of several files (2026-11-17, j74)

- **Found where first named, as a source launcher finds them** (measured against JDK 25's
  `java app/Main.java`): `JavaClasses.qualified` misses the table and the library, then reads
  `<root>/<package>/<Name>.java` (`programClass`) through `JavaFiles`, which the seam makes
  over the read's `SourceLoader` (`SourceLanguage.javaFiles`); the root is a `../` per name of
  the starting file's package above its directory. Every class of a file read joins the table
  (`Polygon` beside `Square` is found once `Square` is). A file that does not declare the
  class in the package its path spells is `bad source file: .. file does not contain class
  p.Q`; a starting file whose directories do not spell its package is `end of path to source
  file does not match its package name p` (the launcher refuses a single file so too). No
  file (`-e`) reads no other file.
- **The unit of lowering is the program.** A class found adds to the table, so the pass is
  lowered again (the table's version, as for a library class); the direct calls and the
  dispatch sets see every class of every file. A class of another file is the program's
  (`Source.library` false): lowered whole and located (`located` keeps a provenance unit and
  line table per file). So a read of a `.java` file is the read of the program it starts:
  `(load "app/Main.java")` from Common Lisp runs it as `rontolisp app/Main.java` does, on both
  paths (the interpreter's `loadFile`, `LoadInliner` on the compile path hand the read their
  loader); a file with no `main` is refused as an entry. Two loads whose programs share a
  class are two closed programs: the second defines the shared names again, its dispatch
  sets with them.
- **Imports**: a single-type import of a class of the program is resolved where it is
  written (a compiler's `cannot find symbol` at the import), one of the library where it is
  used, so a program holds only the library classes it uses. A static import
  (`Unit.statics`, `staticOnDemand`) is checked where it is written (`cannot find symbol:
  static m`, `cannot find symbol: class C`); a simple name that is no local, field or member
  of a class around reads the imported field (`variable`), an unqualified call no class
  around has a method of is the imported class's static methods, every single-static import
  of the name together (`imported`), a type name the imported member type.
- **Access across packages** (`JavaClasses.accessible`, `JavaLowering.checkAccess`): a class
  not `public` named from another package, a member with no modifier and a `protected` one
  from outside its package and its subclasses are a compiler's errors (`X is not public in p;
  cannot be accessed from outside package`, `m() has protected access in C`). Only the
  program's classes are checked; the library trusts itself. Not checked: the receiver rule of
  a `protected` instance member and a `protected` constructor reached by `new` from a
  subclass (`deviations.md`). javac drops an inaccessible candidate from the overloads
  (`no suitable constructor found for A(no arguments)`); ours is chosen, then refused by its
  access.

## A library from the source path (2026-11-19, k24)

**Invariant: a library a Java program uses is its SOURCE, lowered with the program on every
backend as far as the program reaches it; what the program reaches and the subset lacks
is refused by name where it is reached, never where a class is read.** Asked for YAVI
("YAVI compiled to wasm" below holds the milestone; the measurement is
`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`).

- **Source, not bytecode.** A dependency's `-sources.jar` (or a directory) is read through
  the same `JavaFiles` as the program's tree (`JavaFiles.library`, a path below a root),
  so the one lowering serves the four backends. A `.class` front end was weighed and not
  taken: it would be a second front end with its own semantics (erased generics, no
  constant folding as the language computes it, `invokedynamic` for lambdas and string
  concatenation, synthetic bridges), every case of the corpus would need a second leg,
  and a jar's classes call the JDK's by descriptor, where the library here is found by
  source name and static type. What it would add is jars that ship no sources; of YAVI and
  jspecify both publish one.
- **Surface.** `--java-source-path DIRS-AND-JARS` and `--java-source-dep G:A:V`
  (repeatable; `cli/JavaSourcePath`), carried to the seam in
  `SourceStandards.javaSourcePath` and made a `JavaFiles` by `SourceLanguage.javaFiles`
  (a jar's entries listed once a read, a file known as `root.jar!/path`). **Not
  `--java-dep`**: that is class files for the host's class loader, refused with a `.wasm`
  output and copied beside a compiled jar (`NAME-lib/`, the pom's dependencies); a library
  on the source path is compiled INTO every output and leaves nothing to carry, so one
  flag for both would have a jar carry classes its program never calls
  (`JavaClassPathCliTest#aJavaProgramCompilesItsLibrariesFromTheSourcePath`).
  Coordinates resolve through `am.ik.maven` as `--java-dep`'s do (`--java-repository`
  serves both): the graph's runtime class path, so compile and runtime scopes
  transitively, a dependency's `provided`, `test` and optional ones not, and of each
  artifact the `sources` classifier. A coordinate NAMED with no sources jar is refused; one
  it depends on with none is a warning (javac needs only its class file, and the program
  may never reach it). The Maven plugin's `wasm` goal passes a project's dependencies
  (`jvm-export.md`, "The plugin's `wasm` goal"); `-e` and a session read the roots
  ("A session" below). **The playground has none** (k32, 2026-11-20): its files are text
  (`rontoPutFile(name, content)`), so a jar cannot be uploaded as one, and it has no
  resolver. Its uploads are the program's source TREE instead ("The playground's uploads"
  below).
- **A program's file wins**: `JavaClasses.programClass` asks the source tree, then the
  roots in order (javac's order of a source path). A class of a JDK's packages is never
  looked for on either.
- **Three origins** (`JavaClass.Origin`, on `Source`): `PROGRAM` lowered whole, located,
  checked; `LIBRARY` (the jar's own) lowered as reached, trusted, not located;
  `DEPENDENCY` lowered as reached (`JavaLowering.isPartial`: the `reached` / `made` /
  `used` sets `lowerReached` already kept for the library), LOCATED (its file and line are
  an uncaught exception's `at`: `geo-sources.jar!/geo/Table.java:52 in
  geo.Table.row(Object)`) and checked for access. What a compiler already checked of a
  library is not checked again: `@Override`, the abstract methods left out, the flow, the
  `throws` clauses, the bounds of type arguments, the warnings.
- **A class nobody has is a name until reached** (`JavaClass.absent`, `JavaClasses.lenient`
  / `absent` / `withdraw`). While a dependency's DECLARATION is read, a class it names
  that cannot be found, that the subset lacks or whose own file is refused becomes a
  `JavaClass` holding the refusal, outside the table. It stands in member types, where
  identity is enough: a method taking a `java.util.Currency` is one overload among the
  others, and `String` is no subtype of it. Asking what it is made of -- its supertypes
  (`isSubclassOf`), its members (`supertypes`, `field`, `constructors`), a test against it
  (`instanceTest`) -- throws `JavaClass.Absent`, which `expression` turns into the
  refusal at the expression that asked. A class whose supertype is absent is itself
  withdrawn (javac's `class file for X not found`). A type written in a BODY is resolved
  as ever, so a reached body refuses at its position, and `lowerReached` appends how the
  program came there (`reachedThrough`, from `JavaClasses.reachedFrom`):
  `.. CharSequenceConstraint.java:147:8: the class java.text.Normalizer is not supported ..`
  / `  reached through am.ik.yavi.constraint.CharSequenceConstraint.<init>()` /
  `  reached through ..ValidatorBuilder.constraint(..)`. Rejected: dropping a member
  whose signature does not resolve (an overload taken away can change which one a call
  picks: `f(null)` beside `f(Object)`), and refusing the class (YAVI's `ValidatorBuilder`
  named `java.time` in its `constraint` overloads before the library had it, so no
  program would have got past it).
- **Declared whole, lowered as reached.** A class named is parsed and its members' types
  resolved, which reads the files they name: the classic YAVI program declares 171 of 380
  files (24,858 of 183,636 lines), the schema one 327 (175,141 lines) in 1.7 s, and all 380
  parse in 0.76 s. Resolving a member's types only when its name is looked up was not
  needed at these numbers. What is lowered is what is reached: 713 forms and 575,271 B of
  wasm for the classic program, 7.9 s of `-o book.wasm` wall time.
- **Annotations** ("Annotations" below): of a dependency, one whose type is on no root is
  dropped, as javac reads a class file whose annotation type is missing from the class
  path without a word -- jspecify is `provided` in YAVI's pom and is not in the graph.
- **A class in the file of another class is found** (k44, 2026-11-20;
  `JavaClasses.declaring`): a library was compiled whole, and its binary jar holds the
  class file, so a program and the library's own classes reach the class as they would
  through a class path. Measured on JDK 25: `lib.Uses` calling `Hidden`, declared in
  `lib/Lists.java`, is `cannot find symbol` under `javac -sourcepath src` and under the
  source launcher with `lib/` in its tree, compiles and runs against the jar of the
  library compiled whole, and a program naming `lib.Hidden` against that jar is `Hidden is
  not public in lib`. So a ROOT is searched and the program's source tree is not (the
  launcher's rule). Such a class is not `public` (JLS 7.6), so it is in its package's
  directory: on a miss, the `.java` files of that directory under every root
  (`JavaFiles.libraryPackage`, a jar opened once a package, `SourceLoader.loadArchiveEntries`,
  its texts kept for the reads after) are read once into the names their texts spell after
  `class`/`interface`/`enum`/`record` (`JavaParser.declaredNames`, a `String.indexOf` a
  keyword), and only a file spelling the name asked for is lexed for a declaration outside
  every brace (`JavaParser.declaresTopLevel`); that file is declared whole. Every simple
  name a library's class resolves is first a miss in its package (`am.ik.yavi.core.String`),
  so nearly every package the program reaches is read: measured on `Booking`'s lowering in
  one process, cold, lexing every file of a package cost 0.3 s (YAVI's 380 files, 8.8 MB:
  354 ms cold), a scan by characters 0.3 s too (305 ms), a zip opened for each entry
  ~0.1 s; the keyword search reads all 380 in 75 ms cold, and the lowering is 5.6 s cold
  with and without the search. The modules (`Booking`, `Member`) are the same bytes.
- **Resources**: a sources jar holds them (k44: maven-source-plugin packs
  `src/main/resources`; YAVI 0.18.0's carries `META-INF/services/..Processor` and its
  native-image `reflect-config.json`, netty's `META-INF/native-image/..`). Read since k53:
  "Resources" below.
- **What the front end lacked that a library showed** (each a rule of the language, fixed
  for every program, pinned by the fixture):
  - An **exact method reference** naming a class counts in applicability by what its
    method returns (JLS 15.12.2.2; `JavaLowering.gives`): `Book::title` picks
    `constraint(ToCharSequence, ..)` among fifty overloads taking a function. Until then
    every overload of the arity fitted and the call was ambiguous. An implicit lambda
    there is ambiguous under javac too.
  - A **capture turned back into a wildcard keeps the bound it took from its parameter**
    (`JavaTypes.uncaptured`): through `CollectionValidator<T, ?, ?>`, `toCollection()
    .apply(t)` is a `Collection` by `N extends Collection<E>`; it was `Object`.
  - A **permitted class in a file of its own** read while the `permits` clause naming it
    is being read is checked once the clause is whole (`JavaClasses.permitted`).
  - `java.io.Serializable` (a marker in the library's source), `new String(int[], int,
    int)` (`Support.codePoints`, the refusals a JDK's), and a simple name that is an
    imported class of a JDK's packages says `the class java.text.Normalizer` and not
    `cannot find symbol: variable Normalizer`.
- **YAVI's source is not in the repository**; the fixture is a library written here
  (`java-spec.yaml` `a-program-uses-a-library-read-as-source-from-its-source-path`:
  `library:` is zipped to a sources jar for the four legs and compiled by javac for the
  JDK leg, `provided:` is what javac alone reads).

## Resources (2026-11-20, k53; a computed name, bundles, services: k58; URLs, a computed service: k69; directories, the playground's uploads, a bundle class only a computed name finds: k75)

**Invariant: a resource is found while the program is lowered, by the loader a source
launcher would give the class, and its octets travel in the output: a constant name's one
file where the call is, and for a name computed where the program runs every file its
loader may find, in one table the program carries (`JavaResourceTable`).** Measured against JDK 25.0.5's
`java -cp libcls app/Main.java` (the class path holding the library's classes and
resources, NOT the tree: with no `-cp` the default `.` is often the tree's top, and its
directory rule then answers for the launcher -- `../top.txt` found -- which misled the
first measurement).

- **Two loaders** (`java/lang/ClassLoader.java`, two objects of a private subclass). The
  launcher's in-memory loader holds the program's classes and finds a resource in the
  SOURCE TREE first (a tree `lib/label.txt` wins over the class path's), then its parent's;
  the parent is the application loader, `getSystemClassLoader()`, whose class path holds
  the library and which loads its classes. So `JavaLowering.loader`: a `PROGRAM` class
  the program's (tree, then roots), a `DEPENDENCY` class the system's (roots only), a class
  of the library or an intrinsic none (the bootstrap loader: `String.class
  .getResourceAsStream("/top.txt")` is null under a JDK). `getClassLoader()` of a JDK class
  and of a primitive type is `null`; `getContextClassLoader()` is the program's; the
  program's loader is not the system's (launcher: `false`).
- **Names.** `Class` drops one leading `/`, else prefixes the package of the class (an
  array's element class; a nested or anonymous class's top-level package). A loader finds
  nothing of a name starting `/` (`"//top.txt"` beside a class, `"/app/data.txt"` of a
  loader: none, measured). In the tree the name is matched as written: a `.`, `..` or empty
  segment finds nothing (`JavaClasses.treeResource`; launcher measured: `../top.txt`,
  `./data.txt`, `sub//x.txt`, `sub/../x.txt` none). Under a DIRECTORY root of the source
  path they are resolved (`JavaFiles.below`, a `..` out of the root none: a class path
  directory measured so), in a jar the entry is the name exactly. A directory is a
  resource (k75, below).
- **Lowering.** `Name.class.getResourceAsStream(c)` and `.getClassLoader()` are answered
  where they are written (`literalResource`, beside `getName`'s road), so no class object
  is made. Any other `Class` receiver (`getClass()`, a `Class<?>` held) goes through the
  natives `Class.getResourceAsStream(String)` / `getClassLoader()` (`byClass`): a
  `Class<X>` with `X` no wildcard is `X`'s object alone; otherwise the program's and the
  libraries' classes under the bound, grouped by what each answers, in a `cond` over
  `Class.located` (the binary name at run time), anything else `nil` -- so a library's
  `getClass().getResourceAsStream("note.txt")` reads the SUBCLASS's package, as a JDK
  does. A loader's `getResourceAsStream` is `(if (ClassLoader.searchesTree l) tree
  (and (ClassLoader.searchesRoots l) roots))` where the tree has another answer (k69: the
  platform's finds none), `getSystemResourceAsStream` the roots'. A computed name (k58)
  takes the same roads with `ResourceTable.beside(reach, directory, name)` /
  `find(reach, name)` as the answer, the receiver then the name read once
  (`classResource`'s `let*`), a `null` name the NPE every class gives.
- **The octets** travel as a text of one character an octet (`Resources.stream`, a new
  `ByteArrayInputStream` every read). Not their UTF-8 text: one road for every file,
  binary or not. Measured (wasm): a program printing 5 octets 41,606 B (its
  `ByteArrayInputStream` over a literal's `getBytes()` 42,424); a 200,256-octet resource
  adds 200,155 B of ASCII and 300,160 B of random octets (1.5 an octet: a character above
  127 is two in the string data); 200,256 random octets read back hash-identical on the
  four backends, NUL included. A program naming no resource is byte-identical (a thread
  and class-object program 107,548 B, `examples/java/shop` 328,166 B, before and after).
- **Roots.** The tree is the entry's (`JavaFiles.findResource`), the playground's uploads
  (`treeResource`; an upload's octets are its text's UTF-8), none for `-e`; the roots are
  `--java-source-path` / `--java-source-dep` (`libraryResource`). The Maven plugin's `wasm`
  goal (k59) passes `${project.build.outputDirectory}` (`classesDirectory`) as the FIRST root,
  ahead of `<javaSourcePath>` and the dependencies' sources jars: javac's class path order,
  and it holds the resources as the resources plugin left them (filtering, includes, excludes,
  `targetPath`), which `src/main/resources` read in place would not. It is left out when it
  does not exist (`mvn rontolisp:wasm` before `compile`). Staleness (`WasmProgram`) covers
  every file of the tree and of a directory root, not only `.java` (a changed resource
  rebuilds); pinned in `MavenBuildE2eTest` (a filtered `app.properties`, changed, rebuilt).
- **A computed name (k58): carried, not read where the program runs.** Measured first:
  every call naming a resource in the class files of the 753 libraries of the local
  repository (the k58 artefacts' `Scan.java`, the class-file API following each argument
  back to what pushed it; 198 of them call one): of 278 `Class.getResource*` calls, 129
  name a constant, 69 a concatenation, 55 a parameter, 16 a call's result, 9 a field; of
  396 loader lookups 86 a constant and 147 a parameter; `ResourceBundle.getBundle` 61 of
  114, `ServiceLoader.load` 68 of 110 (31 a parameter). A name a library computes mostly comes from elsewhere (a parameter, a
  field: YAVI's `ResourceBundleMessageFormatter` keeps its base name in a field and asks
  the thread's loader), so no shape of the call bounds what it finds. Two answers were
  open: the roots read where the program runs (the Clojure front end's: a jar reader in
  `clojure.lisp`, a `--dir` preopen on wasm) or the files carried. Taken: carried, as k53
  carries a constant's -- a `.wasm` with no file system, a component a host serves, a
  `.class` moved off the machine that compiled it all find them, which reading the
  compile machine's `~/.m2` and tree at run time would not. The Clojure front end keeps
  its reader (a Ring `:root` serves any request path).
  - **What is carried** (`JavaClasses.carry` / `carryBundle`, `JavaResourceTable`):
    selections, each made once and noted with `changed()`, so a pass lowered before the
    first lookup is lowered again: a computed name selects every file of every root, and
    of the tree where the program's loader may be asked (a program class, any loader
    receiver, `getBundle` with a loader); `getBundle` of a constant base name its
    `.properties` files in one directory. Left out: `.java` and `.class` (a source jar's
    sources, the classes of `target/classes`, which k59 makes the first root; a constant
    still finds a `.java` file, k53), names starting with a dot (a repository's `.git`),
    directories. A directory's entries in name order, so the table does not depend on the
    host. Bounds: 10,000 files, 16 MiB, 100,000 entries looked at, refused at the lookup
    (`unsupported`: `a resource named where the program runs while the source tree holds
    more than 10000 files to carry`) -- a tree whose top is a home directory. The
    playground's uploads are listed since k75 ("The playground's uploads").
  - **The table** (`ResourceTable.table0()`, a native answered by
    `JavaClasses.carriedTable()`): one text, the tree first then the roots in order, each
    place a letter (`T`, `D`, `J`), its count and `;`, each file its name's length, `;`,
    the name, its octets' count, `;`, the octets one character each. `ResourceTable.find`
    walks the text where it is asked, the tree by the name as written, a `D` root by the
    name `below` resolves, a `J` root by the name as it is. First drafted as maps parsed
    in a static initializer: a program computing one name was 194,314 B of wasm against
    43,195 B for the same read by a constant (`HashMap`, `ArrayList`,
    `Integer.parseInt`); walking the text it is 50,285 B. A program computing no name has
    no `ResourceTable`: `examples/java/shop` (328,166 B) and a constant read (43,195 B) are
    byte-identical before and after.
- **`ResourceBundle` (k58)**: `getBundle(base)` and `getBundle(base, locale)` are natives
  (`JavaLowering.bundle`) naming the loader of the class calling them (a library's: the
  system's, which misses the tree: probed), `getBundle(base, locale, loader)` the one
  given. The documented lookup: candidate locales `lang_COUNTRY`, `lang`, root (the
  locales `Locale` has); at each a public class of the bundle's name under
  `ResourceBundle` (`classBundle`, a native answered with a `cond` over the classes
  `bundleClassNames` lists beside a constant base name -- `Texts.java`, `Texts_*.java` in
  its package's directory -- and, once a base name is computed, every bundle class in the
  table), else `<name>.properties` read as UTF-8 or, where it is not well formed, ISO
  8859-1; the first found the result, the next its parent. A base bundle alone, or none,
  sends a locale other than the default to the default locale's candidates (probed under
  four `-Duser.language/-Duser.country`: the root asked finds the base and stops, a
  locale finding only the base takes the default's). One bundle per loader, name and
  locale (`MADE`): `getBundle(US)` and `getBundle(CANADA)` answer one object. A
  session's `getBundle` carries every file, a constant base name too: `ResourceTable`'s
  text is read once, and a later buffer may name any bundle. Orders read
  off the answers: a `PropertyResourceBundle`'s own keys are a `HashMap` copied from the
  `Properties` it read, a `ListResourceBundle`'s a `HashMap` of `contents.length` (3 keys
  in 4 buckets, probed against 16), `getKeys` its own then its parents' it lacks,
  `keySet` a `HashSet`; `getObject` asks the parent's `getObject`, so a missing key is
  refused with the LAST class's name. The words: `Can't find bundle for base name B,
  locale L` (class name `B_L`, key `""`), `Can't find resource for bundle C, key K`.
- **`Properties` (k58)**: a `Hashtable` whose mappings are a `ConcurrentHashMap` (of the
  capacity given): a JDK's orders its keys as that map does (probed: 40 keys under six
  capacities, 300 under none). `propertyNames()` is a
  `Hashtable` and `stringPropertyNames()` / `list` a `HashMap`, each filled from the
  deepest defaults up (probed by simulating each candidate over 40 keys); a key or value
  that is no `String` is a `ClassCastException` in `propertyNames`, `list` and `store`.
  `load` is the documented grammar (natural and logical lines, `\uxxxx`, a continuation's
  leading blanks; 53 lines probed, the corpus's); `store` writes the comments (a line end
  then `#` unless `#` or `!` follows, a character above `0xff` as `\uXXXX`), the date
  line (`java.util.Date`'s text here), the own entries by sorted key (JDK 18 on), a key's
  spaces and a value's leading ones escaped, and in the `OutputStream` form every
  character outside `0x20`..`0x7e` as `\uXXXX` of its UTF-16 units (a string's element
  here is a code point: `load` joins an escaped pair back into one).
- **`ServiceLoader` (k58)**: `load` of a `Class<S>` of an exact `S` is a native
  (`JavaLowering.services`) that reads `META-INF/services/<binary name>` of the tree
  (the program's loader) and the roots in order, a name once, `#` to the line's end a
  comment, and resolves each provider (`JavaClasses.binaryNamed`, `$` for a member
  class) to an outcome: not found (or a tree class for the system loader), not a
  subtype, no public constructor of no parameters (an inner class, an interface), not
  instantiable, not public, made. The loader is built by `ServiceLoader.with` calls, a
  maker `(lambda () (|C.new()|))` and the class object's for `Provider.type`. Read off
  the answers: an iterator fails in `next` for every outcome (its `hasNext` answers
  true); a stream fails at its advance for the first three and in `get` for the rest; a
  `get` makes a new instance each time; the iterator's instances and the stream's
  providers are cached apart, each going on from where its lookup stopped;
  `load(S.class, null)` is the system's, `loadInstalled` finds none. The maker and the
  type are raw lambdas `ServiceLoader` holds as `Supplier`s, so `provider` makes `Supplier`
  a lambda target: k58 left that to the program, and one whose only `Supplier` was a
  provider's failed in `get` with `get() has no implementation for its receiver` (k69;
  pinned by `a-program-of-several-files-finds-its-classes-by-package`).
  - **A service named by no class literal (k69)**: a `Class<?>`, a `Class<T>` in a
    generic helper (31 of k58's 110 scanned calls pass a parameter) is told at run time:
    `byService` is a `cond` over `Class.located` among the services the `META-INF/services/`
    files of the loader's places name (`JavaClasses.serviceNames`) whose class the program
    has (a `Class` object the program holds is of a class it has), each arm the chain of
    the exact case; any other class a loader of none. An interface is under `Object`
    (`isSubclassOf` answers false for it, which the bound check must not ask).
  - The platform loader finds no provider: `load(S, platform)` is a loader of none
    (probed).
- **The JDK leg's default locale is `en_US`** (k58): `JavaSpecE2eTest.runOnJdk` passes
  `-Duser.language=en -Duser.country=US`, the subset's `Locale.getDefault()`, so a line
  that reaches the default locale (a bundle's fallback) is not the host's.
- **URLs (k69)**: `getResource` of a class or a loader, `getResources`,
  `getSystemResource(s)`. Measured against JDK 25.0.5's launcher
  (`.todo/artefacts/k69-java-resources-urls-a-computed-service-and-what-k58-left/`):
  - **What a URL names**: the file where it was found -- the tree's
    `file:/<tree>/<name as written>` (the launcher's in-memory loader: `Path.toUri`), a
    directory root's `file:/<dir>/<name resolved>`, a jar's `jar:file:/<jar>!/<entry>`.
    The tree's and a directory's have the authority `""` (`file:///..` read, printed
    `file:/..`), a jar's `null`; the host `""`, the port -1. The escapes differ by place
    (every ASCII character probed in each): the tree escapes space `" # % < > ? [ \ ] ^ `` ` ``
    `{ | }` and a character past ASCII by its UTF-8 in capitals; a class path root `;` and
    `=` too, in small letters, and a character past U+FFFF as its two surrogates' three
    octets each (`😀` is `%ed%a0%bd%ed%b8%80`). A name holding `:` is found by no class
    path root (not carried over: the subset finds it).
  - **Order**: `getResources` of the program's loader is the tree's file, then each root
    holding the name in the source path's order; the system's the roots only. A
    directory is no resource (k53's deviation): the launcher answers a URL of it
    (`getResource("")` beside a class the class's directory, of the program's loader the
    first class path directory), a listing through `openStream`.
  - **The loaders above**: the program's parent is the system's, whose parent is the
    platform's, whose parent is `null`; the platform's finds no resource, bundle or
    provider. `ClassLoader` holds a reach (0 the platform's, 1 roots, 2 the tree and the
    roots): a constant name is `(let ((l ..)) (if (searchesTree l) tree (and
    (searchesRoots l) roots)))`, a computed one `ResourceTable.find(reach, name)`;
    `ResourceBundle.called` takes the reach too. `new ClassLoader()` of a program is
    under the system's (its resources the roots'), `new ClassLoader(parent)` reaches as
    its parent.
  - **The URL carries the octets**: `URL.located(kind, place, name, octets)` (the
    lowering's form for a constant, `ResourceTable.located0` for a computed name) makes
    one with its fields set, no text read; `openStream` streams the octets. Any other URL
    reads nothing: `FileNotFoundException` (`deviations.md`), a URL made again from a
    resource's text included -- the alternative, a table of URLs to octets, would carry
    every constant read twice for a round trip a program rarely makes (`Path.of(uri)`, the
    usual one, has no `java.nio.file` here).
  - **The table** names each place's path (letter, count, `;`, the path's length, `;`,
    the path) only once a URL is asked of a computed name (`carryPlaces`): a program
    reading streams carries an empty one. `ResourceTable.find` keeps its own walk; the
    URLs' `search` (which hands back where the place and the octets are) is a second one,
    so a program reading streams does not carry it: written as one walk, the program
    computing one name grew from 47,499 to 50,600 B of wasm.
  - **`java.net.URL`** is written from the API documentation and the answers to 98 probe
    lines (corpus `a-url-is-cut-into-its-parts-as-a-jdk-cuts-it`): the protocols a JDK
    25 has a handler for (`file ftp http https jar jmod jrt mailto`), the spaces and a
    `url:` ahead of a text dropped, the port's refusals with their index, the host's
    characters (controls, space, `" < > \ ] ^ `` ` `` { | }` and DEL refused), two `@`
    leaving no user and no host, a jar's `!/` and its inner URL, an empty authority
    printed as none. `equals` / `sameFile` compare the protocol, the host's text ignoring
    case, the port or the default one, the file and (equals) the fragment, not the user;
    `hashCode` is a number of our own, equal for equal URLs. `URI.toURL` refuses a
    relative URI (`URI is not absolute`) and reads the text. The checks are ASCII: a
    first draft took `Character.isLetter` and `toLowerCase` and pulled the Unicode
    tables, 56 KB of wasm for a program asking one resource's URL.
  - **Measured** (wasm, 2026-11-20, a tree under the scratch directory): a constant
    `getResourceAsStream` 41,682 B before and after; a computed one 47,499 -> 47,759; a
    loader's constant read 43,616 -> 43,902 (the reach check), computed 49,071 -> 49,724;
    a constant `getResource(..).getPath()` 27,191; a computed `getResource(..).openStream()`
    80,358, and with a `FileNotFoundException` the program throws anyway 80,478 against
    71,711 for the same stream read.
- **A member class as a bundle (k69)**: a base name with a `$` (`app.Outer$Texts`) finds
  the classes nested in `Outer` (`bundleClassNames` reads `Outer.java` of the base's
  package and walks its members) named as the base or as it and `_`; a JDK finds a
  public static one with a public constructor of no parameters, skipping a non-public or
  an inner one (probed; `app.Outer.Texts` with dots finds no member). The files are
  `Outer$Texts_en_GB.properties`, which the base's path already selects.
- **A directory (k75)**: measured against JDK 25.0.5's launcher with a tree, a class path
  directory, a jar holding directory entries and one holding none
  (`.todo/artefacts/k75-java-resources-what-k69-left/`, every line of which the four
  backends now print, the listing's order aside):
  - **The tree** (the launcher's in-memory loader): a directory named as written, with or
    without a last `/` (`sub`, `sub/`, `sub/emptydir`), its URL `file:/<tree>/<name>/`
    (always the slash: `Path.toUri`); its stream the names of its entries, each and `\n`
    (a directory's no slash; `.java`, `.class` and dot names included). The top (`""` of
    the loader) is none -- the loader's `""` is the first class path directory's --, a
    file with a last `/` (`sub/a.txt/`) none, `sub//` and `sub/./` none.
  - **A class path directory**: the name's `.` and `..` resolved (`app/./sub`,
    `app/sub/deeper/..`), the top `""` the directory itself; its URL keeps the slash the
    name ends in or that a last `.` or `..` leaves (`app/sub/.` is `.../app/sub/`), a FILE's
    too (`app/sub/l.txt/` finds the file, URL `.../l.txt/`), a `//` kept (`lone//x`: here
    `lone/x`, not carried over).
  - **A jar**: the entry exactly, a directory only where the jar holds its entry
    (`jaronly/`; a jar written with no directory entries has no directory resource), read
    as empty (`JarURLInputStream` there, a `ByteArrayInputStream` here); `""` and
    `jaronly/.` none.
  - **The order of a listing** is the launcher's default locale's collation (`_u`,
    `.hidden`, `10`, `9`, `a.txt`, `Alpha`, `b.txt`: punctuation first, case ignored at the
    first level), so it depends on the host that runs the program. Here it is the names'
    characters (`String.compareTo`), fixed when compiled (`deviations.md`); the corpus lists
    only directories where the two agree.
  - **Where**: `JavaClasses.treeResource` answers a directory's listing
    (`listing`) and notes it (`treeDirectories`, for the URL's slash); `inDirectory` and
    `inArchive` the roots' (`JavaFiles.libraryEntry`, a jar's own entry); a constant name's
    stream goes through `located` now, the one road (`JavaFiles.libraryResource(String)` is
    gone). For a computed name the table carries each directory a `*` selection walks as a
    file whose name ends in `/` (a directory root's top the empty name, the tree's top
    none), its octets the listing; `ResourceTable.matches` takes a name or the name and a
    `/`, and `located` gives the URL its slash.
  - **Measured** (wasm P1, 2026-11-21, a tree of five files): a constant stream, loader
    stream, `getResource(..).getPath()`, `examples/java/shop` byte-identical (39,683,
    41,104, 26,957, 314,223 B); a computed class stream 45,258 -> 45,860, loader stream
    47,041 -> 47,572, `getResource(..).openStream()` 75,427 -> 76,642; `getBundle` of a
    constant base name 253,415 -> 253,841 and of a computed one 254,817 -> 255,342 (a
    bundle's `.properties` read goes through `ResourceTable.find`, which holds the
    directory match). The listings are what a large tree pays: a computed `getBundle` over
    a tree of 2,003 classes in 41 directories carries ~18 KB of names.
- **A bundle class only a computed base name finds (k75)**: the launcher's loader compiles
  the class `getBundle` asks for, which nothing else of the program named; k58 made only
  the bundle classes the program had. `JavaClasses.declareBundleClasses` (once a loader's
  reach) reads the `.java` files of the tree (where the loader looks there) and of every
  root, and declares those that hold a class, nested or not, under `ResourceBundle`,
  `ListResourceBundle` or `PropertyResourceBundle`, or under a class so found, by the
  simple name its `extends` names (a fixed point); only a file whose text holds one of the
  names is parsed. Declaring every file was the alternative the todo feared: a class of the
  program is lowered whole, so an unused file of the tree with a type error would refuse a
  program the launcher runs (probed: `Unused.java`, `return "s"` from an `int`, stays
  unread here). `isBundleClass` still decides. Measured: a 2,003-class tree compiles in
  5.9 -> 6.0 s; with YAVI's sources jar (415 files) as a root 6.2 to 6.7 s before and
  after (a box under load); the program gains the bundle classes it makes (5.1 KB for a
  `ListResourceBundle` and its `en_GB` subclass).
- **`ResourceBundle.Control` (k75)**: measured against JDK 25.0.5 (the k75 probes; the
  corpus's `controls()` keeps a program's `Control` logging every call). `getBundle` with a
  `Control` asks `getFormats` once, then for each locale from the target on through
  `getFallbackLocale`: `getCandidateLocales`, and the candidates from the LEAST specific
  up (the parent first, a recursion), each the `newBundle` of each format until one is
  made, then `getTimeToLive`; the base bundle found for an earlier locale stands for the
  root of a later one (not asked again). It stops at a bundle that is not the base, or is
  of the locale asked, or of the one candidate there is. A bundle kept (by loader, base
  name and locale, whatever the `Control`) is not asked for again: a second `getBundle` of
  `tables.fallback` with a logging `Control` logged no `newBundle`, under a JDK too. A
  bundle `newBundle` makes directly has no name and no locale; `getBundle` sets them. An
  exception `newBundle` throws is the cause of the refusal if nothing is found.
  `getControl` / `getNoFallbackControl` answer one object a format list
  (`ResourceBundle$Control`, `ResourceBundle$NoFallbackControl`), another list is
  `IllegalArgumentException` with no message; `newBundle` of an unknown format `unknown
  format: x`. Not kept: a time to live other than `TTL_DONT_CACHE` keeps the bundle for
  good (`needsReload` is never asked, `deviations.md`); a JDK's helpful `NullPointerException`
  texts.
  - **One road**: `getBundle` without a `Control` runs the same walk with `control` null
    (`found`, `chain`, `made`): the default answers are static methods (`candidates`,
    `fallback`, `bundleName`, `properties`) the library's `Control` calls too, so a program
    that gives no `Control` holds none, makes no loader object and has no `catch` of
    `newBundle` (`Control.made`). Measured (wasm P1): `getBundle` of a constant base name
    253,841 -> 255,430 B (the walk the JDK's order, a refusal's cause); written first with
    `Control.INSTANCE` as the default it was 268,514 (`List.of` of the formats, the
    `Control` class and its static fields, the `catch`). A `Control` given: 268,854.
  - **Lowering**: `JavaLowering.bundle` tells the forms by the last argument's class (a
    subclass of `ResourceBundle$Control`, or `java.lang.Module`); a `Control` or a module
    carries every file and declares every bundle class, as a computed base name does -- a
    `Control` may name any file (`toResourceName`) and any class (`toBundleName`).
- **`getBundle(.., Module)` and `Class.getModule()` (k75)**: `java.lang.Module` is the
  unnamed module of a loader (`ClassLoader.getUnnamedModule`, one a loader) or `java.base`
  (`Module.BASE`, a JDK class's and a primitive type's: `module java.base`). `getModule()`
  is a native (`ClassLoader.moduleOf` of what `getClassLoader` answers by class): written
  first in Java as `ClassLoader.moduleOf(getClassLoader())` in `Class`, it answered
  `java.base` for a program class -- a `getClassLoader()` in a body of `Class` is told by
  `byClass` over a `Class<T>` of the type variable, which the native's road does not
  reach (not looked into). `getBundle` of an unnamed module asks its loader; of
  `java.base`, `Can't find bundle for base name B, locale L` (probed). It reflects on
  nothing else.
- **`Properties.storeToXML` / `loadFromXML` (k75)**: written from the API documentation,
  XML 1.0 and the properties DTD, every answer a JDK's (98 probe lines; the corpus's
  `properties()`). Writing: the declaration with the charset's canonical name
  (`utf-8` -> `UTF-8`, `latin1` -> `ISO-8859-1`), the DOCTYPE, a `<comment>` unless the
  comment is null or EMPTY, the own entries in the table's order (not sorted, as `store`
  is), `&amp; &lt; &gt;` and in a key `&quot;`, a character the charset lacks or past
  the first plane or a lone surrogate as `&#x..;` (in UTF-8 too: `&#x1f600;`), `\t`,
  `\r`, a control character raw; a key or value that is no `String` is `ClassCastException:
  Keys and values in Properties must be Strings` before a byte is written; the stream is
  flushed, not closed. Reading (`rontolisp.internal.PropertiesXml`): the charset of a mark,
  of `<?` in two octets (UTF-16 with no mark), else of the declaration (an unknown one
  `UnsupportedEncodingException` of its name in capitals), else UTF-8; line ends one
  `\n` (`&#13;` kept); an attribute's tab and line end a space. A JDK's reader is lenient
  where the DTD is not, and so is this one: text outside an entry and an attribute other
  than `key` are dropped, a `comment` after an entry is taken, an entry inside an entry
  starts a new one (`{b=}`), an entity no declaration names is nothing in text (and refused
  in a value), `&#0;` is a NUL, `PUBLIC` with the DTD's URI passes. Refused, each with the
  JDK's words after `jdk.internal.org.xml.sax.SAXParseException; `: no DOCTYPE or another
  root or URI, an internal subset, a root other than `properties`, an element not
  declared (`properties` below the root included), an entry with no key, a second
  comment; a document not well formed with nothing after the `; ` (a JDK's parser says
  nothing there either); `&#x110000;` is the `IllegalArgumentException` of `Character`.
  The refusal has no cause (a JDK's is its parser's exception, `deviations.md`). Measured
  (wasm P1): a program storing one entry 213,706 B, loading one 229,934 (`store` of one
  244,487, unchanged).
- **Left** (`.todo/k82`, its locale bullet): the candidate locales of a script and a
  variant and the Norwegian and Chinese cases `Control`'s documentation states, once
  `Locale` has such locales.
- **Beside it** (k53): a qualified name of a JDK package naming no class the subset has,
  read as a value (`java.util.ResourceBundle.getBundle("m")`), is `the class
  java.util.ResourceBundle` and no longer `cannot find symbol: variable java`
  (`JavaClasses.namesPlatformClass`: a JDK package's name no prefix of which is a class).
- Pinned by java-spec `a-program-uses-a-library-read-as-source-from-its-source-path`
  (`resources()`: the JDK leg's library classes carry the `library:` entries that are no
  `.java` beside them; k58's `computed()`, `bundles()` and `services()` with the tree's and
  the library's `.properties`, bundle classes and services files),
  `a-deque-an-optional-the-utilities-and-the-older-classes` (`properties()`), k69's
  `urls()`, the providers of a `Class<?>` and a member class's bundles in the first,
  `a-url-is-cut-into-its-parts-as-a-jdk-cuts-it`,
  `a-program-of-several-files-finds-its-classes-by-package` (a provider the one
  `Supplier` of a program), k75's `directories()` in the first (a directory of the tree by
  a constant and a computed name, a bundle class only a computed base name finds),
  `JavaLoweringTest#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition`
  (the qualified JDK classes),
  `JavaLoweringTest#aProgramOfSeveralFilesIsReadFromItsSourceTree` (a tree of more files
  than a program carries),
  k75's `controls()` there (the library's `Control`s, a program's logging one, a module's
  bundles) and the XML lines of `properties()` in the second,
  `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram` (an
  upload read as a resource, by a constant and a computed name),
  `PlaygroundReplTest#aClojureRequireReadsTheUploadedDepsEdnLikeEveryOtherRoute` (the
  uploads' directories to Clojure and Common Lisp).

## YAVI compiled to wasm (2026-11-20, k33)

**A program over YAVI (`am.ik.yavi:yavi:0.18.0`, Apache-2.0) compiles from the unpatched
coordinates and prints a JDK's lines on the interpreter, the JVM, wasm and the component,
in both of the library's styles**: `ValidatorBuilder` since k29 (2026-11-19), the schema
(`Yavi.schema()` .. `fromMap`) since k37 (2026-11-20). This was the milestone k24's
mechanism was asked for.

- **The examples**, each checked in `examples.yaml` on the four (`javaSourceDep`):
  `examples/java/yavi/Member.java` (wasm 663,174 B) and `examples/java/yavi/Booking.java`
  -- a schema of a string, an enum, a `LocalDate` and an integer read from a map and,
  through `readingParameters()`, from a form's strings: wasm 1,204,017 B, the component
  1,208,399, the jar 453,397 (2026-11-20, k26: 194 and 652 B over k45's 662,980 and
  1,203,365, the pipeline's record and the third function of a format); on the exec jar
  the interpreter 7.5 s,
  `-o booking.wasm` 15.3 s. Both are written here, like the fixture. The Maven plugin builds such a project
  (`jvm-export.md`, "The plugin's `wasm` goal").
- **What stood between** (each fixed for every program, in the order met): annotations
  and the rules of the language under "A library from the source path"; the linked
  collections (k27), `MessageFormat` (k28), `Normalizer` (k29); a `Class` as a value and
  four rules of inference (k30); `java.math` (k31), `java.time` (k35), `UUID` (k36),
  `java.util.Date` and `java.sql` (k37), `StreamSupport` (k38). The refusals by number:
  `.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/README.md`.
  YAVI was not changed.
- **Where the bytes go** (`-Drontolisp.wasm.debug-func-sizes`, the functions by what they
  belong to, `Booking` after k45; the scripts and every column as k33 measured them:
  `.todo/artefacts/k33-yavi-compiles-to-wasm-the-milestone-and-what-stands-before-it/README.md`):

  | | `Member` | `Booking` |
  |---|---|---|
  | the file | 662,980 | 1,203,365 |
  | data section | 146,506 | 219,745 |
  | YAVI's functions | 47,762 | 184,749 |
  | the regex engine | 130,631 | 132,203 |
  | `java.util` | 54,622 | 92,781 |
  | `java.time`, `java.math` | 0 | 127,483 |
  | `java.net` and the URI runtime | 0 | 29,626 |
  | the library's rest | 29,286 | 45,940 |
  | lambdas | 46,861 | 55,563 |
  | dispatch | 16,151 | 40,938 |
  | the top level and the program | 7,032 | 28,718 |
  | the runtime's rest | 175,393 | 229,529 |

  YAVI itself is 7 % of the classic example and 15 % of the schema one: the fifty
  `constraint` overloads are not it, only what is called is lowered. The runtime under any
  program and the regex engine are 46 % of the first. The data section has no such dump.
- **The regex engine is in every YAVI program** (130 KB, `regex-engine.md`, "Measured"):
  a string constraint reaches `CharSequenceConstraint`'s three static patterns and
  `VariantOptions`' `replaceAll`. Without the three patterns the classic example is 937 B
  smaller and the engine is still there.
- **A schema of 0.18.0 pays for every type YAVI reads an input as**, whichever it asks
  for: `core/UntypedInput` holds them in one table and one method. A schema of a string
  and an integer is 1,144,352 B (1,216,741 before k45); 951,244 (-16.9 %) over a copy of
  YAVI without the table of the types read from their text, the `java.util.Date` /
  `java.sql` arm and the `Year` arm; 889,037 (-22.3 %) without the conversion of a number
  through `BigDecimal` too. The library classes were owed either way.
- **Split in YAVI by https://github.com/making/yavi/pull/604** (k46, 2026-11-20, to
  `develop`, open): the callers that know the type are in `builder` and `arguments`, whose
  only way into `core` was `ValueValidator.untyped(Class)`, which converts any type. Added
  public API, nothing changed: `UntypedConversions` (one conversion a type, `toInteger()`
  .. `toUri()`, `toEnum(Class)`, `instanceOf(Class)`) and
  `ValueValidator.untyped(Class, Function)`, which the typed builders and validators name;
  `untyped(Class)` picks among the same conversions. Over that branch's sources (the k33
  scripts with `--java-source-path <checkout>/src/main/java`), each printing the JDK's
  lines on the four:

  | | 0.18.0's code | the pull request |
  |---|---|---|
  | the schema of a string and an integer | 1,145,000 | 947,719 (-17.2 %) |
  | `Booking` | 1,204,017 | 1,040,183 (-13.6 %), the component 1,044,555 |
  | `Member` (no untyped input) | 663,174 | 663,174 |

  Gone: `java.net` and the URI runtime, all of `java.time` from the schema and all but
  `LocalDate` from `Booking` (97,001 -> 18,135 B of code). `java.math` stays (29 KB): an
  integer may be given as a `BigDecimal` or a `double`, and one read from a form's string
  is one; the rest of the way to the 889,037 bound is that conversion, which YAVI's rules
  keep. The examples name 0.18.0, so their numbers above stand until a release has it
  (`.todo/k50`). YAVI's `scripts/generate-args.sh` (the builders, `DefaultArguments1Validator`)
  runs to its end only under bash 3.2 (`docker run bash:3.2`; 5.1 and 5.2 stop early
  without failing), and its last step needs `perl`.
- **A variable named `set` put the eval runtime in `Booking`** (fixed by k45): `java.net.URI`'s
  runtime (`eval/uri.lisp`) has a parameter named `set`, and both backends' gate for the
  eval runtime counted any mention of `set`, `symbol-value` or `fboundp`. On wasm that was
  `_eval`, `_apply` and `_dispatch_spread` (one case per callable, 53,761 B): `Booking`
  1,280,340 -> 1,203,365 B, the component 1,284,722 -> 1,207,747, the jar 464,923 ->
  453,082, the schema above 1,216,741 -> 1,144,352. The gate now asks whether the name is
  called, taken by `#'` or quoted, or a place written (`compiler/FunctionNameUses`). The
  `URI` entry of the table costs that schema 36,426 B (108,815 before), 29,626 of them its
  own code.

## A session (2026-11-17, j74)

**Every buffer lowers the whole session again**: the declarations the earlier buffers left, kept
as TEXT, after the buffer (`JavaSession.read`: `buffer + "\n;\n" + earlier`, the buffer's
positions its own; `JavaParser.session`, statements allowed below the boundary only), and the
setup step defines every function again. So a call and a dispatch see every class and body
declared so far, and a later buffer's subclass, overload or new body reaches an earlier caller
by name (the interpreter calls a function by its name). Rejected: a call table a later buffer
replaces (every call of a closed program indirect, two lowerings to keep) and lowering the
affected declarations again (the closure of what a change reaches is the program). Measured
2026-11-17, the interpreter, a buffer after N declarations (a method and a class calling it,
alternating, then a call, 100 buffers timed): 120 declarations 10 ms a buffer, 480: 28 ms.
- **What a buffer holds** (`Snippet`): an import, a type (a top-level class), a method (static,
  of the session's class `$Session`), a variable (a static field of it, no initializer), a
  statement, an expression. A statement or an expression is a step method `$step<k>`, called by
  its own step inside `(handler-case .. (error (c) (%java-session-error c)))`: what it left
  uncaught is one line, `Exception java.lang.X: message`, and the REPL goes on. The buffer ends
  in a made-up `;`, so the last statement needs none; `isComplete` reads the buffer alone and
  takes a missing `;` at its end for complete, any other end of input for more.
- **Replacement** is by key: a type by name, a method by name and erased parameters as written,
  a variable by name, an import by its text; a buffer's declarations drop the earlier ones of
  their keys BEFORE the text is put together. The session's state changes only once the buffer
  lowered: a buffer that fails leaves it as it was.
- **Echo** (`JavaSyntax.Echo`, a step answering a string): `label ==> value` by the static type
  -- a string and a `char` as literals (`%java-echo-string`, `-char`), an array as
  `int[2] { 1, 2 }` (`%java-echo-array`), anything else as string conversion writes it. An
  expression is stored in `$<n>` (numbered per expression, a `void` one included, as a snippet
  shell numbers snippets), a variable in itself, an assignment `x = v` shows under `x`.
- **Inferred types**: a `var` variable and `$<n>` are fields whose type the Echo sets (the step
  methods are first in the class, so a later step reads it typed; the table moves, the pass is
  lowered again). The next buffer's text spells the type (`JavaSession.spell`: qualified, a
  local or an anonymous class as its supertype, a captured wildcard as the wildcard).
- **What does not run again**: every class is lazy (`JavaInitOrder.allLazy`); a class's
  defaults and its `<clinit>%done` are set only when the buffer declares it or no buffer set it up
  (`Session.declared`, `initialized`), so a static field lives on. A session variable is a
  `defvar` with its type's default (it holds one when its step failed); a variable whose type
  names a class the buffer declares again is set to `null` in the setup (its instance has the old
  slots). What an earlier buffer's values may be is carried: the library classes made
  (`Session.made`, a dispatch must reach an `ArrayList` no later buffer names), the interfaces a
  lambda was made for, every one tagged in a session (no later buffer may find an untagged one
  it must tell apart), and whether `Thread` was reached (`monitors`).
  An enum's `$VALUES` array is added where a session declares the enum, not where a buffer
  first names `values()`: that buffer finds the enum initialized by an earlier one, and
  nothing would set the field (k40, 2026-11-20;
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot`).
- **No source tree**: a session has none, as `-e` (the REPL's working directory is no
  package root, and a snippet shell reads none either). **The source path it has** (k32,
  2026-11-20): `JavaSession` takes the `JavaFiles` of `SourceLanguage.javaFiles` over the
  standards' roots, and `JavaClasses.programClass` with no entry file asks only `library`;
  `-e` read them already through `SourceLanguage.read`. The roots' entry lists are read once a
  session, the files again with every buffer as the whole session is lowered again.
  `JavaClassPathCliTest#aJavaProgramCompilesItsLibrariesFromTheSourcePath`. The
  playground's session has its uploads as a tree ("The playground's uploads" below).

## The playground's uploads (2026-11-20, k44)

**The uploaded files are the source tree of the page's program, which is read from no
file**: a class it names is read from the upload its qualified name spells (`geo/Place.java`,
`Helper.java`), as a source launcher reads a file beside the one it starts at -- lowered
whole, located and checked as the program's own (`Origin.PROGRAM`), on the interpreter
(`PlaygroundRepl.run`, a buffer of the session) and on both compile buttons
(`RontoPlayground.frontend`) alike.
- **Why a tree and not a root**: a root's file is a library's, trusted and lowered as
  reached ("A library from the source path"), because its authors compiled it; nobody
  compiled an upload, and `javac -sourcepath` checks a file it reads there as one it was
  given. The page has no file name to root a tree at, and `-e` has none either and reads
  no file, so the tree is a standard of its own: `SourceStandards.javaSourceTree`, `""` on
  the page (`PlaygroundRepl.STANDARDS`, a name resolved against nothing is the name as
  uploaded), `null` everywhere else; `JavaFiles.tree` reads it where `programClass` has no
  entry file. A buffer keeps its positions without a file name, and one declaring a
  package is not refused for where it is not.
- **The page uploads a file by its base name**, so an uploaded file is a class of the
  unnamed package; `rontoPutFile("geo/Place.java", ..)` spells a package's.
- **The uploads are a directory (k75, `eval/UploadedFiles`)**: the names' slashes make its
  directories (`listDirectory`, `exists` of a directory), the top `""`, `.` or `./`. So a
  name computed where a Java program runs finds an upload, as one in a tree does. Measured
  first what the listing changes in the page's other languages (a probe through
  `PlaygroundRepl` with the old loader and the new): Common Lisp's `directory`,
  `probe-file "geo"`, `uiop:directory-exists-p`, `directory-files` and `subdirectories`
  answered `nil` and now the uploads; Clojure's `:local/root "lib"` was `Local lib my/lib
  not found` and now reads `lib/deps.edn`, and `(io/file "geo")` is a directory; a WIT
  package directory (`wit/gl/gl.wit`) was refused (`a WIT package directory is read only
  where the loader lists directories`) and now binds. Each was the answer of a host with no
  directories, not one a user chose. `PlaygroundReplTest` and `WitImportInlinerTest` take
  the page's loader itself, no longer a copy of its shape; `web/playground-check.js` reads
  an upload by a computed name on the image's interpreter and its `.wasm` button.
- Pinned by `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram`
  (a run, a buffer, an upload's error at its own position) and `web/playground-check.js`
  (the image: a run and the `.wasm` button over an upload).

## Across languages (2026-11-17, j74)

**Nothing is converted but a `char` and a `boolean`; the door is a call, both ways**, as
[starlark-frontend.md](starlark-frontend.md)'s.
- **Common Lisp -> Java**: `(load "Geometry.java")` (`SourceLanguage.readLoaded`, both load
  paths): a file with a `main` runs it, one with none defines its classes and runs nothing
  (`Java.load`, `JavaLowering.loaded`; the CLI entry still refuses no `main`). A static method
  is the function `|Class.name(erased,params)|` (`JavaNames.function`: the class's Lisp name,
  `java.lang` simple, a package's qualified), a constructor `|Class.new(params)|`.
- **Java -> Common Lisp**: a `native` static method of a program (`JavaForms.lispFunction`, an
  emitter as the library's native members are): the function named by `JavaForms.lispName`, a
  hyphen before a capital after a lowercase letter or a digit, upcased (`stringUpcase` ->
  `STRING-UPCASE`, `toJSON` -> `TO-JSON`), resolved where the forms are evaluated. A `char`
  argument is `code-char`ed, a `char` result `char-code`d, a `boolean` result `(if r t nil)`, a
  `String` result `%java-lisp-string`. An instance `native` method is refused.
- **Values**: a number is a number, a string a string, `null` `NIL`, a program's object a
  structure, an `Object[]` a simple vector. A Lisp list is no `List` (its dispatch has no arm for
  a cons): a vector crosses. Pinned by java-spec `common-lisp-and-java-call-each-other` (a
  `.lisp` standalone, no JDK leg; four backends).

## Annotations (2026-11-17, j74)

Parsed and dropped (`JavaParser.annotations`), on a declaration and since k24 on a type
(`Function<A, @Nullable B>`, `String @Nullable []`, `java.util.@Nullable List`), but the
name has to be an annotation type the file sees: `java.lang`'s five (a set, no file read),
those of `java.lang.annotation` (library source), or one declared with `@interface` (k24:
an interface flagged `isAnnotation`, its elements methods, a `default` skipped to its
`;`). The parser only records `(position, name)` (`CompilationUnit.annotations`; by
position, since a probe looking ahead reads one twice) and `JavaClasses.annotation`
resolves it after the file's classes are declared: `cannot find symbol: class X`, or
`incompatible types: X cannot be converted to Annotation` for a class that is none (both
javac's). An unknown name is refused because it is the kind a processor or a framework
gives a meaning whose absence would change the program. Decided for a dependency by what
javac does with a class file: an annotation whose type is missing from the class path is
dropped silently, so a library's file is not asked (`Source.partial`). Until k24 the
parser itself refused any name but the five (the `Known` error, gone). `@Override` and `@FunctionalInterface` ride in the modifiers and are checked
(`JavaLowering.overrides`: a supertype's or `Object`'s instance method of the signature seen
from the class, or a record's accessor; `checkFunctional`). `@SafeVarargs` rides there too and
`@SuppressWarnings("unchecked")` is a span of the file (j86, "Warnings" below).

## The tools (2026-11-17, j74)

`SourceLanguage.isSourceFile`'s readers: `rontolisp build` names no system (as before);
`rontolisp test` refuses a `.java` file (no test framework; a program's status is its verdict:
it used to read the file as Lisp, run it and report no tests); `rontolisp format` refuses a
`.java` and a `.star` named explicitly (re-indenting is no whitespace-only change there; a
directory walk never took them). The Maven plugin's `compile` goal compiles `.lisp` under
`src/main/lisp` only (`LispSourceSet.EXTENSION`): a `.java` file of a Maven project is javac's,
and a subset program compiled to a class of the same name would collide with it. Its `wasm`
goal compiles the program `src/main/java` holds to a `.wasm`, which collides with nothing
(`jvm-export.md`, "The plugin's `wasm` goal").

## Declarations and statements past the first slice (2026-11-17)

- **A nested class's name** is its canonical name (`Outer.Inner`) in every Lisp name and its
  binary name (`Outer$Inner`) for `getClass().getName()`; a local class is `Outer$1Local`, an
  anonymous one `Outer$1`, numbered in the order written in the class's body by a walk of the
  tree (`JavaClasses.number`), as a compiler names them (the lowering meets them in another
  order: methods, then initializers). A type name is looked for innermost first: a local class
  in scope, the class and its member types, inherited ones included, then each class around.
- **A class of a compact source file is a member of the file's class**, an inner class unless
  static, a record, an enum or an interface -- measured against a JDK 25: `new A()` in a static
  method of such a file is `non-static variable this cannot be referenced from a static context`
  there too, and `getName()` is `Compact$A`.
- **An inner class holds its outer instance** in a slot `this$<depth>` (the depth of the class in
  its `extends` chain, so the slots of a class and its superclass never meet), set by the
  function making the instance ahead of the constructor; a superclass that is an inner class
  gets the instance of ITS outer class found from the subclass's (`outerInstance`). A member of
  a class around is read through the chain of those slots. Where the subclass's own outer
  instance does not hold one (`givesSuperOuter`), its constructors give it: `o.super(..)` stores
  `(or o (%java-npe))` in the superclass's slot ahead of the superclass's constructor, and a
  constructor with neither that nor `this(..)` is the compiler's `an enclosing instance that
  contains O.I is required`; an anonymous class made with `o.new I() { }` takes it as an argument
  of the function making it (`JavaClass.superOuterGiven`, `%super-outer`). Ahead of `this(..)` or
  `super(..)` the instance is out of reach but its outer instance is set already, so
  `Outer.this` reads it there (`enclosing`). **`Name.this` is the instance of `Name`
  itself, lexically around** (k30, `lexicallyAround`): until then it was the nearest
  instance of a class UNDER `Name`, as a member's implicit receiver is, so an anonymous
  `new Step<..>() { .. Step.this.apply(a) .. }` made in a default method of `Step` named
  itself and ran for ever (YAVI's `Arguments1Validator.this.validate(..)`).
- **`super.m()`, `Outer.super.m()`, `I.super.m()`** call one body directly (`superCall`): the
  superclass's, the superclass's of the class around on the instance around, the default of an
  interface the class names in its `implements`. `I.super::m` stays refused.
- **A local and an anonymous class are lowered where they are declared**, once a pass, behind a
  scope that marks the class's boundary. A local read across the boundary is a CAPTURE: a slot
  `val$<name>$<depth>` of the class, set where an instance is made from the value the name has
  there (which is the enclosing local class's own slot when the creation stands in one). By
  value, so the local must be effectively final -- which the flow decides ("The flow" below).
  Rejected: lowering the class's methods as closures (a method is a `defun` taking `this`;
  dispatch and `super` calls name it).
- **An anonymous class's constructor** takes the parameters of the superclass constructor its
  arguments pick and hands them on (`Method.passesTo`); it is made where the class is created.
- **An enum** extends the library's `Enum` (source); a constant is a `static final` field whose
  initializer makes the instance behind two parameters every constructor takes first, the name
  and the ordinal (`Method.hidden`); a constant with a body is an anonymous class under the
  enum. `values()` and the `$VALUES` array, and `valueOf`, are added where a program first names
  them (`JavaClasses.enumMethod`): `valueOf` throws, and a program that does not name it holds
  no exception class (measured: an enum program naming only `values()` went from 83 to 60
  forms).
- **A record** extends the library's `Record`; its accessors, canonical constructor (a compact
  one is the written statements, then the stores) and `toString`/`equals`/`hashCode` are
  members in the subset itself, the last three parsed from text the class table writes
  (`JavaParser.synthetic`, every node at the record's position). `equals` compares a `double`
  by `Double.compare`; `hashCode` is `31 * h + hash` over the components (unspecified by the
  language; a `double` component's is `Double.hashCode`, since j71).
- **A switch is a `cond` over the selector**: `eql` for an `int`/`char`, `equal` for a string,
  `eq` to the constant's field for an enum, a pattern's own test; the default clause last
  wherever it is written. Colon cases none of which runs into the next are the same `cond`, an
  empty case giving its labels to the next. Cases that do run on are numbered: the selector
  picks the first to run and each runs under `(if (<= picked i) ..)`, inside `(block %break ..)`
  -- no `tagbody`; a switch expression's the same, its `yield`s leaving `(block %yield ..)`, the
  last case falling into `%java-no-match` (`runOn`). A local declared in a colon case is the
  whole switch's: hoisted to one `let`. A `null` selector reaches `%java-null-switch` unless a
  case names `null`. A switch expression with no target type is lowered twice: once to find the
  type its arms share. `case` would be no faster than this `cond`: it expands to the same chain
  of `eql` tests (measured below).
- **A pattern's binding is a variable of the statement the pattern stands in**, declared by a
  `let` around the statement and set where the test holds:
  `(and (Circle? o) (progn (setq c o) t))`; an `if` or a `?:` testing it runs the stores at the
  head of its branch instead, `(if (Circle? o) (progn (setq c o) ..) ..)` (`branch`): the truth
  value the `and` makes and the `if` tests again cost a loop of pattern tests 9% on wasm and
  the component, nothing on the JVM, and binding `c` by a `let` in the branch measured the same
  as the variable once that value was gone (below). Where it is in SCOPE is the language's: `flow`
  answers the bindings a condition leaves when true and when false, `&&`/`||`/`!`/`?:` and
  `if`/`while`/`for` put them in scope, and a statement whose branch never completes leaves the
  other's bindings to the end of its block (`introduced`). Rejected: a `let` per binding nested
  into the condition (the else branch is duplicated or needs a block).
- **What a set of patterns covers** (`JavaPatterns.covers`): rows of patterns against columns of
  types; a sealed type is covered when every type it permits is, a record when its record
  patterns cover its components together. A switch expression, and a statement with a pattern
  or a `null`, must cover; one that does ends in `%java-no-match` instead of a default.
- **A labeled statement** is a `block` named `<label>%break`, a labeled loop's round
  `<label>%continue`; an unlabeled `break` of a labeled loop goes to the same block.
- **`this(..)`/`super(..)` after statements**: the constructor's body is lowered as written, the
  call where it stands, followed for `super(..)` by the instance initializers; ahead of it the
  instance is refused (`early`) but for the store of a field and the instance around it.
- **A `final` local with a constant initializer is a constant variable**: its reads carry the
  value (`Local.constant`, `constantValue`), so `while (DONE)` and a `case` label read it as the
  language does.
- **A default method** is the body `implementation` answers when no class above declares one;
  a dispatcher tests the topmost class inheriting it. A lambda called through a default runs
  the default (`functionp` arm).
- **A variable arity method** fits in a third phase, after the fits without and with boxing.

## The flow (2026-11-17, j77)

**What a compiler decides from the flow is decided after the lowering, per class, over the
tree (`JavaFlow`), as a compiler's flow analysis follows its attributed tree:** reachability and
normal completion (JLS 14.22), definite assignment and unassignment (16), effective finality
(4.12.4), and since j99 the checked exceptions a body throws (11.2: "Checked exceptions"
below). `program()` lowers a top-level class with its member classes (`lowerTree`), then
runs `JavaFlow.check` over the whole of it (`flow`): a compiler types the whole top-level
class before its flow, so an error of typing in a later member or method comes ahead of
any flow error of the class (k06; until then each class, a local one included, was flowed
as soon as it was lowered). A library class is not checked. Reported as the compiler
reports, the first problem of the kind it reports first: reachability, then assignment,
then exceptions, then captures. Of one kind, asked of javac 25 (k06, 41 probe programs,
`.todo/artefacts/k06-java-front-end-what-the-checked-exceptions-slice-left/`), each kind
has its own order (`JavaFlow.problems`):

- reachability: the member classes first (each with its own member classes first), then
  the static initialization, the instance initializers, the default constructor, the
  members in order;
- assignment: the class's own -- static initialization, default constructor, members in
  order (a method written ahead of a constructor is reported first, j99) -- and the member
  classes after;
- exceptions: the member classes first, then the class's own as for assignment;
- captures: the first written, over the whole class (`problem` keeps the least position);
- a local or an anonymous class is walked where the walk around it meets its declaration
  (`declared`, once: `Facts.flowed`), its problems joining that walk's there; an
  unassigned local it reads and its own first unassigned variable in the order written.

A class with an error of typing gets no flow at all, and a class after one with a flow
error is typed but not flowed, which is why the first error is all that is reported.

- **What only typing knows comes from the lowering (`JavaFlow.Facts`)**, recorded by node
  identity: the variable each `Name` and `this.f` it resolved stands for (a local's declaring
  node -- declarator, parameter, pattern, enhanced `for`, `catch` -- or a `Field`, `Local.key`),
  the value of each constant condition (`flow`), the enhanced switches (a pattern, `null`, or a
  selector no constant switch takes: they need no default to cover), and the locals each local or
  anonymous class reads (`variable`, at the OUTERMOST class boundary crossed).
- **The walk mirrors javac's analyzers**: bit sets of definitely assigned and unassigned blank
  variables (a local with no initializer, a blank final field), a dead state vacuous in both,
  jumps carried to their targets (a `finally` that completes adds what it assigns, one that does
  not drops them), a loop walked again with what its body assigns unassigned (`might be assigned
  in loop`), a switch case starting from the selector's state, `uninitsTry` for a `catch` and a
  `finally`. A lambda's body sees what is assigned before it and nothing around it unassigned;
  a class declared in a body checks its captures where it is declared. A constructor runs the
  instance initializers at its `super(..)` (or first), all fields assigned after `this(..)`; each
  blank final field is assigned at its end and every `return`; a class with no constructor
  checks its initializers (`not initialized in the default constructor`); the static
  initialization checks the static blank finals.
- **`exits` is the flow's `completes`** over a statement alone: the lowering asks it for a
  pattern binding's scope after an `if`, a `switch`'s run-on cases and a lambda body's shape. A
  condition the lowering has not met yet is no constant there, which can only make a statement
  look like it completes.
- **Rejected: following assignments inside the lowering**, as the first slices did: a lambda's
  body is lowered for each candidate of an overload and again for its type, a switch expression
  twice, initializers once per constructor, and the order of the lowering is not the source's
  (methods before initializers), so what one pass assigned was neither one path nor all of them.

## Type parameters (2026-11-17, j70)

- **Erased, and typed through.** `JavaType.Variable` is a use of a `TypeParameter` (a class's,
  a method's, a wildcard's capture, or one an inference left open); `ClassType.arguments` is
  empty for a raw type. A member's type is its declared type with the owner's parameters
  replaced by what the type it is read through gives them (`JavaTypes.memberType` over
  `asSuper`), erased through a raw type. Nothing of this reaches the forms but through the
  conversions: a `T` that is `Integer` unboxes where an `int` is wanted.
- **A method's name has its erasure** (`Box.above(Comparable)` for `above(T)`,
  `T extends Comparable<T>`).
- **An override through a type parameter is ONE dispatch entry, no bridge**: two methods are
  one when their signatures are equal as the class asked about has them (`signatureIn`: the
  owner's parameters replaced by that class's arguments, then erased), so the dispatcher of
  `Comparable.compareTo(Object)` calls `Version.compareTo(Version)`. Rejected: bridge
  functions (a second function per override and a call more; what a bridge adds is the cast
  that fails on a polluted heap -- stated in `deviations.md`).
- **A wildcard is captured where a member is read through it** (a fresh parameter bounded as
  the wildcard) and each argument of a call once; the member's type is projected back
  (`upward`). `? extends T` therefore takes no value but `null`, `? super T` a `T`.
- **A pattern naming a parameterized class with no argument** takes what the value's static
  type settles (`JavaTypes.refine`), and so does a sealed type's permitted class in
  `JavaPatterns.covers`.
- **A local class sees the type parameters of the method around it** through `JavaClass.scope`,
  taken where the class is declared; `JavaClasses.methodParameters` is the method being read
  or lowered, empty inside `inContext`.
- **A type of an inner member class carries the arguments of the classes around it** (j78),
  after its own: `JavaClass.allTypeParameters` is its own then the outer's (an inner member
  only; a local or an anonymous class reads the parameters around it as they are), and every
  relation that pairs `ClassType.arguments` with parameters (`bindings`, `capture`, `refine`,
  `isRaw`, `self`) reads that list. So `Stack<String>.Cell` (`TypeName.enclosing`),
  `Cell` written in `Stack<T>`'s body or a subclass's (`JavaClasses.enclosingArguments`: the
  class around that is under the declaring one), `s.new Cell()` and `new Cell<>()` give
  `value` the type `String` -- before, it was refused from outside and wrongly `T` inside
  through another parameterization. Rejected: an `outer` component of `ClassType` (every
  relation would have learnt a second list; javac's `allparams` is this flattening). `inScope`
  stays as the guard of a local class's member read from where no type gave the argument.
  Errors as javac's: `cannot select a static class from a parameterized type`, `improperly
  formed type, some parameters are missing` / `type arguments given on a raw type`.
- **A written type argument is checked against its parameter's bounds** (j78,
  `JavaClasses.withinBounds`): where written in a body, at once; in a declaration, once the
  declarations read with it have set their bounds (`boundChecks`, at the end of
  `complete`). A wildcard is not checked. `type argument String is not within bounds of
  type-variable T`.
- **A field written through a wildcard takes the capture** (`Place.stored`): read as the
  bound, written only with what converts to `CAP#1` (`JavaTypes.diagnostic` names captures
  as javac does in a message).
- **An intersection is a type variable no declaration names** (`JavaTypes.intersection`,
  `TypeParameter.intersection`): bounded by each type, so members and subtyping come from the
  bounds (a field too, through any bound: `t.PREFIX` of `T extends A & B`); two are one type
  when their bounds are. A cast `(A & B) x` casts to each in turn (the failing one names
  itself in the exception); a lub of types sharing several least supertypes is their
  intersection, the class first (two records implementing `S` are `Record & S`, which was
  `Record`). A lambda or a method reference cast to one (j86, `intersectionLambda`) is made
  for an interface no declaration names, extending each (`JavaClasses.intersection`, one per
  list of interfaces, its name the names joined by `&` so a session finds it again): its
  function type is the bound's that has the one abstract method, `madeFor` hands the lambda
  that interface as its target, and the tagging of "Lambdas" below then makes it an instance
  of each (`instanceof Marker`) and runs each one's defaults. Errors as javac's: `INT#1 is
  not a functional interface`, `bad intersection type target` (a class but `Object`). A
  conditional or a switch of lambdas in a cast is javac's `lambda expression not expected
  here` (it was accepted).

## Inference (2026-11-17, j70)

One `JavaTypes.Inference` per candidate: what each type parameter IS (`exact`), is ABOVE
(`lower`: an argument's type) and is UNDER (`upper`: the target). Resolution: exact, else the
lub of the lowers, else an upper, else OPEN.

- **Order** (`resolve`): the arguments that are no lambda (each lowered ONCE, with no target);
  the three phases over every candidate; the most specific; then, for the one chosen, the
  return type against the type the value is wanted as; then each lambda and method reference,
  lowered once the types it TAKES are known (a parameter nothing said anything of is its
  bound) and telling the inference what its body gives (`returns`, the switch expression's
  device). A diamond is the same call with the class's parameters to find.
- **OPEN variables stand for the inference a compiler finishes from the target.** A parameter
  nothing was said of becomes a variable every type converts to and from, read as the box its
  context unboxes (`empty()` as an argument, `new ArrayList<>()` anywhere). A call that is an
  ARGUMENT leaves open, above the type found, what only its own arguments said (`flexible`):
  `best(listOf(a, b))` needs `Node<Integer>` where `listOf` alone finds `Leaf`. `ground`
  reads either as a type for `var` and for an emitter. Rejected: lowering an argument again
  with its parameter's type (exponential in the depth of nested calls; the switch
  expression's two passes are bounded, these are not) and a joint inference over nested calls
  (the specification's; the forms do not depend on type arguments, only the conversions do).
- **Through the declared bound** (`incorporate`): a `Leaf` given for `T extends Comparable<T>`
  makes `T` the `Node<Integer>` that `Leaf` is comparable to.
- **A call that is an argument and fails without a target is lowered after the inference**
  (j78, `mayNeedTarget`, `late`): a call whose own arguments hold a lambda or a method
  reference is lowered once with no target as before; when that fails, the state is put
  back (`Standing`) and the call waits like a lambda, lowered against its parameter's type
  once this call's inference has run, its type then told to the inference.
  `words.sort(Comparator.comparing(s -> s.length()))`, `filter(xs, not(s -> s.isEmpty()))`.
  With several candidates of the arity (j86, `Late`), a candidate takes the argument when it
  lowers against that candidate's parameter (state put back after each try, one try per
  candidate and type), and the most specific is chosen by subtyping, not by the lambda rule;
  none taking it is the first error. A failure, not a success, marks it, so a program that
  lowered keeps its forms and its cost. Rejected as before: lowering every such argument a
  second time.
- **Such a call tried against a candidate is tried against what the target says** (k60,
  `tried`): with several candidates of the arity, a parameter of the candidate nothing else
  said of takes what the type the call is wanted as says through the candidate's return type
  (the class's type for a creation with `<>`). `PriorityQueue<P> q = new
  PriorityQueue<>(Comparator.comparingInt(p -> p.rank()))` was `Object.rank() is not
  supported` against all five constructors of one argument, and so was a `TreeSet` or a
  `TreeMap` of such a comparator.
- **One such call given for a variable arity parameter is tried against the array first**
  (k39, 2026-11-20): the phases taking it as the array come before the one taking it as
  an element, so a variable arity candidate takes a waiting argument at its last parameter
  in those phases only when it lowers against the array's type (`Late.lowers`, as for
  several candidates). `Map.ofEntries(Map.entry("a", s -> s))` was lowered against
  `Entry<..>[]` and reported `cannot infer type-variable(s) V`.
- **An array's element type is not captured** (k39, `lowerBound`'s array arm): a wildcard
  meets a wildcard, `Entry<? extends K, ? extends W>[]` given for
  `Entry<? extends K', ? extends V'>[]` says `K <: K'`; captured, it found `K'` a capture
  the array is no subtype of (`cannot be applied to given types`).
- **A lambda for a parameter whose type is the call's type variable waits** (j86): its shape
  says nothing there, so it is lowered once the variable is found -- from the other
  arguments or the target (`Supplier<String> s = pick(() -> "p", () -> "q")`); a variable
  found by nothing is javac's `cannot infer type-variable(s) T`.
- **Each value given is checked against the target** (j86, `conflict`): a type an open
  variable makes fit (`List<OPEN>`) no longer hides another value given that does not
  (`List<String> a = pick(new ArrayList<>(), new ArrayList<Integer>())` was accepted), and
  a type holding an open variable is not counted among the values given when the message is
  chosen (`given`).
- **An exact method reference says what the function takes** (`exact`: one method of the
  name in a class that is not generic, itself not generic nor of variable arity, as JLS
  15.13.1 has it): its parameter types are upper bounds of the function's
  (`fn(B::twice)` finds `Function<Integer, Integer>`); an unbound one's first parameter is
  the class, as before.
- **A switch expression whose arms are lambdas waits for its parameter's type** like a
  conditional of them (`needsTarget`, `accepts`); an arm that is no lambda (`null`, a
  variable) is lowered as the type (k12: `cond ? () -> x : null` as an argument was
  `cannot be applied to given types`).
- **What the collectors needed (k12, 2026-11-19)**, each found by a probe of
  `collect(groupingBy(.., mapping(.., toList())))` over the program's own classes
  (`Gathered` in the corpus) before the library was written:
  - **An argument lowered late is captured as one lowered early is** (`deferred`): a
    `Collector<T, ?, R>` given for `Collector<? super T, A, R>` after this call's
    inference was `incompatible types: .. cannot be converted to ..`, its `?` never a
    type of its own.
  - **Under captures of `? super T` is `T`** (`Inference.resolved`): two upper bounds that
    are captures of one wildcard (a function and a collector both `? super T`, passed on
    by a method of the library to another) had no type under both; what a capture is
    above is under it.
  - **The type a parameter has to be is told through its declared bound**
    (`incorporateExact`): `M extends Map<K, D>` found to be `Map<String, Long>` from the
    type the call is wanted as makes `K` and `D` those. Said once a parameter, at any
    `solution(true)`, since the wanted type may be the first to say it.
  - **An open variable of an argument's type is the parameter it stands at**
    (`lowerBound`, `tie`): `toList()` given for `Collector<T, A, R>` is a
    `Collector<OPEN, ?, List<OPEN>>`, and `R` is now `List<T>` -- so the lambda of
    `collectingAndThen(toList(), l -> ..)` takes a `List<String>` once the target says
    `T`, where it took a `List<Object>`.
  - **A call is resolved once more, its lambda-holding arguments waiting** (`resolve`'s
    outer half, `eager`): where such an argument lowered with no target and the call then
    failed, the state is put back and the call resolved with those arguments late;
    failing again, the first error stands. A failure marks it, as before: a program that
    lowered keeps its forms. Cost on a failing path: one more lowering of the call's
    lambdas (linear in the depth of nested calls, the waiting arguments not being
    lowered in the retry).
- **A type variable in scope is never the one a call finds** (k30, 2026-11-20; each met
  by YAVI's schema example, each `List<T> cannot be converted to
  List<List<List<..>>>`, `lambda expression not expected here` or `no suitable
  constructor` until then). An inference's variables are the callee's type parameters
  themselves, so a call whose argument or target names the same parameter as a TYPE
  solved `T` by a type made of `T` and substituted it into itself:
  - **A method's type parameters are types of their own inside its body**
    (`Method.inside`, made once and kept, since a local class of the body holds them
    over the passes): `method` / `constructor` set `JavaClasses.methodParameters`, the
    return type and the parameters' types (`bindParameters`) through it, `JavaFlow` the
    `throws` clause. So `nest(List.of(value), depth - 1)` in `<T> List<T> nest(T, int)`
    finds the declared `T` as `List<T'>`. Rejected: fresh variables for the callee at
    every call (the `Method` is what `reached`, the effects and the names are keyed
    by, and every reader of its types after the resolution would need the view).
  - **A creation with `<>` finds types of its own** (`diamondArguments`,
    `diamondParameters`): the type it makes is the class given fresh parameters,
    bounded as the class's, which `memberType` puts into the constructor's parameter
    types through the site -- so `new Held<>(List.of(this.held))` in `Held<T>` is a
    `Held<List<T>>`. A raw site keeps the class's own.
- **Two captures share the type variable both are under** (k30, `JavaTypes.above` in
  `lub`): `composed(first, second)` given two `Function<? super A1, ? extends A1>`
  finds `A1` above both captures; until then a capture climbed to the CLASS its bound
  is read through, `Object` for a type variable, and the call was `cannot be applied
  to given types`.
- **A lambda's shape picks among overloads**: its parameter count, and whether its body can
  give a value or none. Only where several candidates remain is a body that is a call asked
  whether it gives nothing (`givesNothing`: lowered once and dropped, state restored), and only
  for a lambda that writes its parameter types or has none -- as the language does. A
  functional interface giving a value is more specific than one giving none.
- **A call that applies only by the unchecked conversion of a raw argument** (k71, JLS
  18.5.2): the type it is wanted as is not told, and its value is the erasure of the type
  returned (`Collections.max(raw)` a `Comparable`; "A raw argument, `Stream.toList()`, the
  digits and the members met").
- **The errors are the compiler's first lines**: `incompatible types: inference variable T has
  incompatible bounds` (one type given), `inferred type does not conform to upper bound(s)`
  (several), `cannot infer type arguments for Box<>`, `method m in class C<T> cannot be applied
  to given types;`, `no suitable method found for m(..)`, `reference to m is ambiguous`,
  `bad return type in lambda expression`. Which of the first two a compiler says was matched on
  eight programs, not derived.

## Lambdas (2026-11-17, j70)

- **A lambda is a function** for any functional interface (`functional`: the one abstract
  method no default above implements, its types through the interface's arguments, a wildcard
  read as its bound). A method reference is the lambda calling the method: `Type::name` is
  lowered as the static call and, when that is refused, as the call on the first parameter;
  `expression::name` binds the expression once around the lambda; `Type::new` is a creation.
  `List<String>::size` is the call on the first parameter seen as the type written;
  `Type::<T>name` passes its type arguments to the call (j78), `Type::<T>new` and
  `new <T>Box(..)` to the constructor (j86: `New.typeArguments`; on a raw reference to a
  generic class, javac's `invalid constructor reference`; with `<>`, `cannot infer type
  arguments`). A method that is not generic ignores type arguments written (JLS 15.12.2.1;
  they were an error).
- **An anonymous class made with `<>`** (j78, `anonymousType`) extends the type a creation
  with `<>` finds -- from the target for an interface, from the constructor's arguments
  (lowered a second time with the class's constructor) for a class --, refused as javac
  refuses when an argument found is a capture (`cannot infer type arguments for G<>`).
- **As an argument it waits for its parameter's type** (k61, 2026-11-20; `mayNeedTarget`,
  `anonymousDiamond`): such an argument, or a call or a `<>` creation holding one, is never
  lowered with no target first (the `eager` try), but after the call's inference, as a
  call holding a lambda is; with several candidates, against each one's parameter. It was
  lowered with no target, extended `ArrayList<Object>` and was refused (`cannot be applied
  to given types; .. found: String,`, the anonymous class's name being empty). A member
  taking its argument as typed (`raw`, `println`) still takes it lowered with no target,
  as javac's `Object` would. A class the expected type cannot be an instance of by its
  class is refused before the body is lowered, with javac's line (`incompatible types:
  cannot infer type arguments for HashMap<>`), so that a candidate whose parameter it
  cannot be (`order(Runnable)` beside `order(Comparator<String>)`) is not taken.
  - **Lowered again against another supertype** (`inferredLowerings`): an anonymous class
    is one `JavaClass` per declaration (`JavaClasses.local`) lowered once a pass
    (`loweredLocals`), so a candidate tried first (`total(Object)` beside
    `total(List<Integer>)`) fixed its supertype, and a lowering that threw marked it
    lowered with no functions. Now its supertype is set anew when a creation finds
    another, and the functions and the classes its earlier lowering in the pass made are
    dropped and lowered again. Not a `classes.changed()`: every pass tries the types in
    the same order, so the passes never settled.
  - **With `<>`, every method it declares that is not private overrides one** (JLS
    15.9.5; javac's `method does not override or implement a method from a supertype`).
- **Measured: `functionp` first is kept.** 20,000,000 calls of a lambda and 20,000,000 of a
  class through one interface, the lambda a function / a structure holding it: JVM 0.76 /
  1.57 s, wasm 1.35 / 1.41 s (2026-11-17, the `bench.sh` method, best of three).
- **A structure only where the program can tell.** `JavaClasses.lambdaTargets` (the interfaces
  lambdas are made for) and `taggedLambdas` persist over the passes. The lambdas of an
  interface become `Iface%lambda` structures holding the function -- and the program is
  lowered again -- when (a) `instanceof` or a cast asks for an interface some lambdas are and
  others are not, or (b) the lambdas a receiver can be would not run the same thing for a
  method (one interface's own method is another's default, or a default overridden below).
  Before j70 (a) was refused and (b) ran the wrong default silently.
- A dispatcher's arms: `functionp`, each tagged interface's predicate, the classes deepest
  first, the library's values (`simple-string-p` .. calling the intrinsic's emitter), then
  `%java-no-receiver`.

## Boxes (2026-11-17, j70)

- **A boxed `long` is `(number . :long)`**, a `byte`, `short`, `float` the same with `:byte`,
  `:short`, `:float` (j72); no other value of a program is a cons, and a box's predicate is
  `%java-long-p` (was `consp`) and its kin. So an
  `Integer` and a `Long` are told apart by `integerp` / `%java-long-p`, by `equals`, `hashCode`,
  `getClass()` and a pattern switch over `Object`, and `equal` -- the `HashMap`'s test -- tells
  a `Long` key from an `Integer` one as `equals` does. Rejected: one value for both (j68;
  `case Integer i` took a `Long`, silently) and a structure in `java.lisp` (the pruner keys a
  bundled structure by name, `.kb/library-defun-pruning.md`; a cons needs nothing).
- **Unboxing tests for `null`**: `(or box (%java-null-unbox))`, then `car` for a `long`,
  `char-code` for a `char`; a literal is not tested. It raises `NullPointerException` (j73).
- **`==` of two boxes compares the values** (`eql`, `equal` for `Long`): a box is its value.
  The language promises it from -128 to 127 and allows it elsewhere. `==` of two strings:
  "The library" below.
- Measured 2026-11-17, before / after the two changes (a 1,000,000-element list summed 20
  times plus 30,000,000 unboxings in a loop): `List<Integer>` JVM 2.47 / 2.75 s, wasm 9.95 /
  9.60 s; `List<Long>` JVM 9.85 / 10.35 s, wasm 32.90 / 41.14 s (the `long` arithmetic owned
  that program). `Fib`, `WrapInt`, `Sieve`, `WrapLong` are unchanged in time and in wasm bytes
  (2,728 / 2,508 / 10,380 / 4,544). After j72's `long` (a program of that shape, 2026-11-17,
  `ListLong` in the j72 measurements): JVM 5.14 -> 1.44 s, wasm 28.02 -> 1.35 s.

## Warnings (2026-11-17, j86)

A JDK 25 source launcher prints a program's unchecked warnings on standard error before it
runs (it compiles with `-Xlint:unchecked`); so does the front end, byte for byte on the
measured programs.

- **The channel**: `Java.read(.., Consumer<String>)` hands the whole text (each warning, then
  `N warning(s)`, at most 100 as javac) to `SourceLanguage`, which prints it as a
  `CompileWarnings.note` -- never counted (`--warnings-as-errors` is about Lisp forms),
  buffered with a compile attempt. The interpreter prints it before the program runs; a
  compile (`-o`, the JVM and wasm alike) prints it once as it reads the source, and the
  compiled program prints nothing, as a `.class` javac made. A session prints none.
- **Gathered while lowering, kept from the last pass** (`JavaWarnings`, one per pass of
  `JavaLowering.lower`): a dropped lowering (`Standing`: a trial, a `givesNothing`, a failed
  first lowering) truncates what it gathered; the same warning twice is one; sorted by file
  (the program's first) and position, as javac's attribution order came out on every
  program tried (a nested class declared first warns first).
- **What is warned** (each where javac puts its caret: a call's `(`, `new`, a field's `.`,
  the start of an expression, a parameter's name): a call or a creation through a raw type
  of a member whose parameters the erasure changes (`unchecked call to add(E) as a member of
  the raw type List`, also an inherited member called on `this` in a subclass of a raw
  type); a raw type converted to a parameterized one where a value is assigned, initialized,
  returned or passed (`unchecked conversion`, and for an argument `unchecked method
  invocation` at the call); a cast to a type variable or to a parameterized type whose
  arguments the value's type does not give (`unchecked cast`; none for an intersection,
  as javac); a field of a raw type written; a variable arity parameter whose element type is
  not reifiable (`Possible heap pollution`) and a call spreading arguments into one
  (`unchecked generic array creation`), but for a `@SafeVarargs` method (the library's
  `List.of`, `Set.of`, `Arrays.asList`, `Collections.addAll`, `Map.ofEntries` carry it as the
  JDK's do). The `where` lines name each type variable the warning mentions, in the order
  mentioned, then those its bounds mention.
- **A call given a raw argument** (k71): the invocation warning names the class raw and the
  argument's conversion names the parameter as declared, said where javac says them (`? extends
  Object` is `?`); a converted value's warning comes after those of its expression
  (`JavaWarnings.Warning.order`; "A raw argument, `Stream.toList()`, the digits and the
  members met").
- **`@SuppressWarnings("unchecked")`** (`JavaParser.suppressed`): the parser records the span
  of each declaration so annotated -- a top-level, member or local class, a field, a method,
  a constructor, a local variable --, and a warning whose caret is inside one is not said.
  `"all"` suppresses nothing: JDK 25's javac still warns under it (measured).
- **The library follows the JDK where a message names it**: `List` and `Set` redeclare
  `add(E)` and `addAll(Collection<? extends E>)`, so `raw.add(x)` names `List`, not
  `Collection`.
- Not warned: a raw value returned from a lambda body, a conditional's arms, a method
  reference through a raw type, an override whose return type converts unchecked; and no
  other category (`[removal]`, `[deprecation]` ...) (`deviations.md`).

## The library (2026-11-17, j71)

- **The split.** INTRINSIC: `Object`, `String`, `StringBuilder`/`StringBuffer` (one
  representation, one predicate), `Number` and the five boxes, `Math`, `System`, `IO`.
  SOURCE: everything else -- the collections, `Arrays`, `Collections`,
  `Objects`, `Optional`, `Random`, `Comparator`, `java.util.function`, the `Throwable` family,
  `Iterable`, `CharSequence`, `java.io` and the charsets ("java.io" below). Three bridges: a `native` method of the source is a form
  (`JavaIntrinsics.NATIVES`, keyed `class.name(erased parameters)`; the parser takes `native`
  only under `library/`); an intrinsic member written in Java is a static method of
  `rontolisp.internal.Support` reached through `Context.library` (`parseInt`, `addExact`: they
  throw the library's exceptions); an intrinsic class implements a source interface, and gains
  the members naming it, once a program names that interface (`Library.implemented` / `meets`:
  `Comparable`, `CharSequence`, `String.join(.., Iterable)`); since j79 any class of the
  source (`String.getBytes(Charset)`, `new String(byte[], Charset)`), and since j87
  `Appendable` (a `StringBuilder` is one: its three `append` as the interface spells
  them, which a dispatcher finds by signature) and `Locale` (`String.format(Locale, ..)`,
  `toUpperCase(Locale)`, the direct `System.out.printf(Locale, ..)`: the locale is
  evaluated and dropped, `after`). `StringBuilder.append` of a range is
  `Support.appendRange` / `appendChars`: Java, because its `IndexOutOfBoundsException`
  is no class of `RAISED` and one more there costs every program with a `catch`.
- **A member `meets` adds is looked for by name before it is there** (j79,
  `JavaIntrinsics.named`, `JavaLowering.bring`): `methods` and `constructors` of an intrinsic
  class first read the source classes the members of that name have in their types. Before,
  a call naming the class only among its arguments -- or not at all: `"abc".subSequence(0, 2)`
  in a program with no `CharSequence` -- found no candidate and was refused, by the order the
  program was written in.
- **Measured both ways before choosing** (`ArrayList` as an adjustable vector, j68, against
  Java over `Object[]`; 20,000,000 `add` then 100,000,000 of one operation, best of three, ns
  an operation): wasm `add` 11 / 46, `get` 17 / 24, the enhanced `for` 16 / 50 to 65, `set`
  32 / 35; JVM `add` 66 / 75, `get` 5 / 6, `for` 6 / 16 (34 when every loop stands in one
  `main`: the JIT, not the form), `set` 10 / 7. The same shapes written by hand in Lisp cost the same. Source
  was chosen: only it finds a key by a program's `equals`/`hashCode`, lets a class of the
  program be a `List` or an `Iterable`, and keeps the JDK's order; the vector answered none.
  Two findings the measurement gave: a Lisp loop of `(setf (aref b i) (aref a i))` is 56 ns an
  element on wasm, so `System.arraycopy` is `replace` (through a `subseq` inside one array);
  the run-time library stores into a non-string vector with `%aset`.
- **Lowered as far as the program reaches** (`lowerReached`): of the source classes only the
  methods a pass CALLED (`JavaClasses.reached`, recorded where `Effects.calls` is), the static
  initialization of the classes it USED, and in a dispatcher, in `%string`/`%equals`/`%hash`,
  `%class-name` and an interface test only the classes it MADE an instance of (`made`; the first
  instance of a class lowers the program again). The three sets persist over the passes. A
  structure is emitted for a made class, the classes above it, and one whose predicate or slot
  the forms name. A program's own classes are lowered whole, as before. A local or anonymous
  class of the library is lowered where its method is.
- **A missing member of a library class is refused by name**, source or intrinsic
  (`Random.nextExponential()`), never `cannot find symbol`.
- **`HashMap` keeps the order a program sees** (the JDK 25 running the corpus is the oracle,
  `a-hash-map-finds-a-key-by-equals-and-keeps-the-order-a-program-sees`): hash folded with its
  upper half, masked by a power of two that doubles past three quarters, the table made at the
  first `put` (16, or the power of two above the capacity asked); a chain in the order put,
  `compute`/`computeIfAbsent`/`merge` putting AHEAD of it; a ninth key of one chain (an eighth
  for those three) doubling a table shorter than 64; `putAll` and the copy constructor sizing
  for `ceil(n / 0.75)`, `HashSet(Collection)` for `max(n, 12)`. Not kept: the tree a longer
  chain becomes in a table of 64 or more. `Set.of`/`Map.of` wrap a `HashSet`/`HashMap`.
  `Hashtable` has its own rules, a class of its own (11 slots, `2n + 1`, a key put ahead of its
  chain, read from the last chain; `a-deque-an-optional-the-utilities-and-the-older-classes`);
  a copy of a map gets the slots the order a JDK walks one in shows -- 11 up to five
  mappings, two per mapping above, the one table size that order fits for each of 2 to 64
  mappings (asked 2026-11-18). `Random()` without a seed is specified only as unlikely to
  repeat: ours steps a counter by 2^64 over the golden ratio and mixes the clock in, and no
  program can tell it from another choice;
  `Vector` extends `ArrayList` and `StringBuffer` is `StringBuilder`'s representation; each
  holds its monitor since j83 ("java.util.concurrent").
- **`LinkedHashMap` is a second chain through `HashMap`'s nodes** (k27, 2026-11-19):
  `HashMap` makes every node through `made(hash, key, value)` and hands every node it
  takes out to `unlinked(node)`; `LinkedHashMap` overrides the two (its `Linked` node
  extends `Node` with `before` / `after`), its views subclass `HashMap`'s and walk the
  chain. Measured before choosing: the two hooks cost a program that makes no
  `LinkedHashMap` 39 B of wasm (149,851 -> 149,890 for a `HashMap` put, merged, removed
  and walked; a `HashSet` 151,534 -> 151,571) and no time (4,000,000 `put` / `remove`,
  0.72 s both); the other way, a list beside an untouched table, has to find a key's
  cell on `remove`, so the table holds the cells and every member of `HashMap` is
  written again over them. Access order (`new LinkedHashMap<>(n, f, true)`) and
  `removeEldestEntry` are `LinkedHashMap`'s alone: each member a JDK counts as reaching a
  key compares the size before and after (`settled`): grown, the eldest is asked for;
  not, the key is moved to the end. Learned from JDK 25's answers: `computeIfAbsent`
  whose function answers `null` reaches nothing although the key is there with a
  `null`; the copy constructor asks for no eldest entry; a move in access order is a
  change (a `get` while walking throws). `LinkedHashSet` is a `HashSet` over a
  `LinkedHashMap` (a package-private constructor). `LinkedList` is cells linked both
  ways under `AbstractSequentialList`, whose `get` .. `remove` walk a program's own
  list iterator (`Index: n` where the walk has no element there, measured). The
  `SequencedMap` members (`putFirst`, `firstEntry`, `reversed` as a view) came with k55
  ("The sequenced collections"). Pin: `java-spec.yaml` `the-linked-collections-keep-the-order-their-elements-came-in`.
  YAVI's classic example prints a JDK's lines in their order with `MessageFormat` and
  `Normalizer` stood in for (wasm 590,666 B, component 594,785, jar 232,947).
- **`==` of two strings is `eq`**, and the constants of a program holding one are globals set
  through `%java-intern` (`JavaClasses.identicalStrings`; the program is lowered again), so two
  constants of a text are one object and `intern()` answers it. A program with no such
  comparison is unchanged. Each setting is a top-level form ahead of the initialization
  (`JavaLowering.internings`, k55): inside the one initialization form, the corpus's 2,296
  of them took that form's JVM method to 65,740 B, past the 65,535 a method holds (a form is
  cut only where [jvm-method-size-limits.md](jvm-method-size-limits.md) says; the rest of
  the initialization left the form with k63, "Static initialization"). `new Object()` is `(make-array 0 :fill-pointer 0)`: no other value
  of a program is a vector with a fill pointer that is no string.
- **`String.format`** is `%java-format` over the argument array and the program's `%string`
  (and, where the program makes a `BigInteger` or a `BigDecimal`, the function writing
  one by its digits: "java.math");
  `%f`/`%e` round the SHORTEST digits half up (`0.15` at one place is `0.2`, measured against
  the JDK), which is why they start from `%java-double-string`. `Double.parseDouble` is exact
  integer arithmetic then `%java-nearest-double` (ties to even, subnormals); `doubleToLongBits`
  is `integer-decode-float`, which loses the sign of -0.0 (tested by the reciprocal).
- **`Math` is `%strict-math`**: the same bits on every backend; a JDK's `Math` may differ in
  the last digit. `System.exit` is `%host-exit` after `finish-output` (not `uiop:quit`: the
  interpreter has no `uiop` in a Java program).
- **Decided: a class we do not have never falls through to the host's.** Probed 2026-11-17:
  `java:call` of `TreeMap.put` with a boxed `long` fails on the interpreter and the JVM
  (`LispCons cannot be cast`), and wasm has no `java:`. This front end's values are not the
  host's objects, so a host class would take a few of them and the program would not compile
  to wasm. Refused by name on every backend.
- A method reference naming a class fits a functional interface by its arity when a call has
  several candidates (`takes`): `thenComparing(Point::label)` against `thenComparing(Comparator)`.
- Sizes on wasm (2026-11-17, before / after): a list added to and read 3,732 / 25,592; one
  printed 43,929 / 56,779; a `HashMap` put and read 37,802 / 57,847. `.todo/k82` holds what is
  left and the leads.
- **The library is Java a JDK compiles** (j79, decided by measuring): javac of JDK 25 takes
  all 139 files as a patch of its own `java.base` (`--patch-module java.base=<library>`) with
  no error -- a `native` member and a raw type are Java. The front end reading it takes more
  than the language (an access across packages, which the library is not checked for: the
  nine errors the first run reported were all j79's own, a package-private member of
  `Charset` reached from `java.io`), so the leg is a test:
  `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles` (3 s).
- **A long chain of a `HashMap`: measured, not written** (j79). Against JDK 25, the order of
  `values()`: 10 keys of one hash code walk as ours; the 11th (the table reached 64 at the
  9th and 10th) makes the JDK's bin a tree, whose root moves to the head of the chain, and
  the order departs (`same: 9 5 3 1 ..` against `5 3 9 1 ..`). How a program gets there: 64
  `Double` keys `x + y / 64.0` (a double of few digits has its hash code in the high bits:
  the JDK walks `2.0` before `0.0`), a class whose `hashCode` is `x ^ y` or `x + y` over a
  16 x 16 grid (256 keys); NOT string keys (4,096 of them) nor `int` keys, which were
  identical at every size tried. The order inside a tree comes from the JDK's own
  red-black insertion, its tie-breaks (class name, `compareTo`, the identity hash) and where
  it links a node: no part of the API, so it can be had only by deriving it from outputs --
  the provenance rule forbids writing it from the JDK's source. `deviations.md` names the
  double keys.
  **Decided 2026-11-18 (j87): not derived.** Measured against JDK 25, where the order
  first departs: `Float` keys `i * 0.5f` at 25 keys (ten of them in one bucket once the
  table is 64; a float of few digits has its low 16 bits zero, so the bucket is its top
  seven mantissa bits), `Double` keys `i * 1.0`, `0.5` and `0.25` at 265 to 272, `i *
  1.5` at 184, `i * 100.0` at 176, `i * 0.1` at 900, `i * 0.01` at 1,800, `i * 0.001`
  not within 2,000; `Long` keys `i << 32` not within 600. The order a JDK shows inside
  such a bucket moves with every put (the 25-key bucket above heads with `1.5`, at 50
  keys its remains with `0.5` ahead of `0.0`), so matching it is keeping the JDK's tree
  per bucket through every put, remove and resize: some 250 lines of the library that
  `put` reaches, in every program with a `HashMap` (a list program is 3.7 -> 25.6 KB for
  less), for an order the API leaves open and that keys with no `compareTo` do not have
  even between two JDKs' objects (it follows their identity hashes). `deviations.md`
  names the `Float` and the `Double` keys.
- **Regular expressions run on the engine the Clojure front end runs on** (j90, 2026-11-18;
  [regex-engine.md](regex-engine.md): `eval/regex.lisp`, written here from the `Pattern`
  documentation, behind `eval/RegexLibrary`). Until then the Clojure engine was a port of
  OpenJDK's `Pattern.java`, which rule 2 of the provenance file forbids, and `String.split`
  read a text or one character class by a splitter of its own. `Pattern`, `Matcher`,
  `MatchResult` and `PatternSyntaxException` are Java source whose `native` members are the
  engine's functions (`JavaIntrinsics.NATIVES`, `engine(..)`), the search state an `Object`
  field. What the engine refuses comes back from `%java-regex-compile` /
  `%java-regex-replace` as a value, `(:refused text index)`, and the source throws it
  (`PatternSyntaxException` holds fields a run-time `%java-raise` cannot make).
  **`Pattern.compile`, `Pattern.matches`, `String.matches` / `replaceAll` / `replaceFirst` /
  `split` are lowered where they are called** (`native` statics and intrinsics over
  `Pattern.make` / `matchesText` / `replaceText` / `splitText`), so the lowering can hand
  the engine's providers by the pattern: a constant is read for `\p`, `\P`, `\b`, `\B`, `\X`
  and the `U` flag (`regexProviders`), any other pattern gets all three -- the Unicode
  tables travel only with a pattern that may read them. **A split by a constant text with
  no operator needs no engine** (`%java-split-text`): `"to be".split(" ")` is 66,664 B of
  wasm where the old splitter made 79,448; a split by a pattern is 251,975 B, by a pattern
  computed at run time 317,580 B. Positions
  count characters, as `length` and `charAt` do. Pin: `java-spec.yaml`
  `regular-expressions-run-on-the-engine-the-front-ends-share` (four backends, a JDK's
  lines).
- **`Arrays.deepToString`** is Java source (j81): `instanceof` over the element, which the
  recorded class answers ("The class of an array").
- **A `Matcher`'s rest (j87)**: `region` with the anchoring and transparent bounds,
  `hitEnd` / `requireEnd`, `appendReplacement` / `appendTail`, `replaceAll` /
  `replaceFirst` of a function, `toMatchResult` (a `Snapshot` of the bounds and the
  input), `hasMatch`; `Pattern.asPredicate` / `asMatchPredicate`. The region and the two
  flags are the engine's ([regex-engine.md](regex-engine.md), "Regions", "hitEnd and
  requireEnd"); the append position and the snapshot are Java. Measured for
  `appendReplacement`: a malformed replacement appends nothing, a group NUMBER the
  pattern does not have appends the input before the match and the replacement up to the
  reference, then throws, and moves nothing on (`Matcher.piece` / `pieced`,
  `%java-regex-expand` answering the part written); a match appended twice is the
  `IndexOutOfBoundsException` of its range. `results` and `splitAsStream` since k12
  ("java.util.stream"). Not there: `usePattern`, `namedGroups`.
- **A program's class named as a `java.lang` source class** (`Iterable`, `CharSequence`,
  `Comparable`, since j87 `Appendable`, which every `Writer` and `PrintStream` names) is
  refused once the program reaches the library's. Counted 2026-11-18 over every Java
  program in the repository (156 files under `examples/`, `doc/`, the test resources and
  the artefacts; the 72 corpus cases): none declares such a class. The Lisp names of
  `java.lang` source classes stay simple.

- **The members everyday code met refused (j98, 2026-11-18)**, each learned as j87's were
  (a grid printed under JDK 25 and under the four backends: 299 lines of the bits, 1,549
  of the parses, 1,488 of `Math`, 287 of the strings). Where each lives: a form of the
  host where the host has one (`Integer` / `Long` over the bits take the width as an
  argument, `%java-highest-one-bit` .. `%java-rotate`; `Math.ceilDiv` is `ceiling`,
  `multiplyHigh` an `ash` of the product, `log1p` .. `getExponent` `%strict-math`);
  Java in `Support` where a member throws a class of the library that is none of
  `RAISED` or walks text (`decode`, `parseUnsignedLong`, `indent`, `Double.toHexString`,
  `clamp`, `absExact`); Java source for the classes (`AbstractMap.SimpleEntry`,
  `StackTraceElement`, `Runtime`, the errors, nine functional interfaces).
  `StrictMath` is `Math`'s class under another name. What the grids found beside the
  new members: the refusal of a parse names its radix when it is not ten and a radix
  that is none has a message of its own (`Support.parseable`); `parseUnsignedInt` reads
  its digits from the left and refuses at the one that passes 2^32 (one loop with
  `parseUnsignedLong`); `Map.entry(..).setValue` says `not supported`.
- **An intrinsic member whose `String` may be `null` says so** (`JavaClass.Method.answersNull`,
  `Builder.nullable()`: `IO.readln`, `System.getProperty`). Every other intrinsic's text
  is concatenated as it is, so `"x" + IO.readln()` at the end of the input was `x` on
  wasm and an error on the JVM, where a JDK writes `xnull` (found by j98; the last line
  of `the-standard-input-is-read-as-bytes-and-through-a-reader`).
- **`Character`'s tests are the Unicode tables' on every backend** (j98). The host's
  `alpha-char-p` and `digit-char-p` are ASCII-only on wasm (`.todo/269`): measured,
  `Character.isLetter('é')` was `true` on the interpreter and the JVM and `false` on
  wasm, and `isUpperCase` / `isLowerCase` answered "has a mapping" (`ª`, `ǅ`). The six
  old tests and the new ones (`getType`, `isJavaIdentifierStart` ..) read the regex
  engine's table of every code point (`%regex-uni-combo`, a running JDK's answers:
  [regex-engine.md](regex-engine.md), "Tables") through `%java-char-combo`, which keeps
  the first 256 code points in a vector. Cost, measured: a program counting letters is
  12,538 -> 42,124 B of wasm (the table's text is 11 KB, the rest its decoder and the
  arithmetic it pulls in); 10,500,000 `isLetter` calls take 0.68 -> 0.99 s on wasm
  (4.37 s before the vector) and 18.6 -> 34.8 s on the interpreter. A program calling
  none holds none. Not moved: `isWhitespace` / `isSpaceChar` (code, as before),
  `toUpperCase` / `toLowerCase` (the host's fold is uniform), and `digit` /
  `getNumericValue`, which asked the host until k71 ("A raw argument, `Stream.toList()`, the
  digits and the members met" below: a list of Unicode's zeros, one rule everywhere).
- **`%h` hands the format the program's `hashCode()` only where the control may ask**
  (`JavaIntrinsics.formatted`): `%java-format` takes the function as its fourth argument,
  `nil` when the control is a constant with no `%h`. The arm itself is 401 B in every
  program that formats (154,299 -> 154,700 B).
- **A field of an intrinsic class naming a source class** joins the class as its members
  do (`named` / `meets`; `select` brings it): `String.CASE_INSENSITIVE_ORDER`, read
  through `rontolisp.internal.CaseInsensitive.order()`.
- **`System.getProperty` and `Runtime` answer what every target can**: the separators
  and the encodings, `null` for any other key; one processor; `gc` is nothing.
  `totalMemory` and its kind stay refused. `System.getenv(name)` is `%host-getenv`
  (k05): the host's variable on every backend, a `.wasm`'s those its host hands it.
- **`java.time.Duration` (k05, 2026-11-19)** is Java source (`java.time` joined
  `LIBRARY_PACKAGES`: it is `java.base`'s), written from the API documentation and JDK
  25's answers: seconds and the nanoseconds past them; where a member reads the amount
  as one signed number it is the seconds toward zero and a fraction of the same sign
  (`whole()` / `fraction()`), which is what makes `toMillis`, `toString` and
  `multipliedBy` one rule each. `multipliedBy` is exact with no big integer (the seconds
  and the fraction have one sign, so their products do; the fraction's product in two
  parts within a long), and its overflow message is the product in decimal, by digits
  of a thousand million. `hashCode` is `Long.hashCode(seconds) + 51 * nanos`: no part
  of the API, read off the probe's answers (a second is 1, a nanosecond 51, -1 second
  0). A probe of 3,321 lines -- every factory over 22 seconds and 14 nanosecond
  adjustments, the arithmetic over 17 durations, 103 texts through `parse` with each
  refusal's message, index and cause -- prints a JDK 25's lines on the four backends
  (`.todo/artefacts/k05-java-front-end-what-the-everyday-members-and-utf-16-slice-left/Dur.java`).
  Not there: `dividedBy` (`.todo/k82`). `between`, `of` / `get` of a `TemporalUnit` and
  `truncatedTo` came with the rest of `java.time` (k35, "java.time" below), which left
  a program of a `Duration` alone as it was (155,029 B of wasm before and after).
  `Thread.sleep(Duration)` is there (a duration below zero is no sleep, zero is one an
  interrupt ends, measured); `join(Duration)` since k08 ("java.util.concurrent").
- **`java.text.MessageFormat` (k28, 2026-11-19)** is Java source (`java.text` joined
  `LIBRARY_PACKAGES`), with no `Format` above it: the pattern's text pieces and argument
  indexes, an argument with no format type only. Learned from a JDK 25 grid of 683
  lines (`.todo/artefacts/k28-java-text-messageformat/Grid.java`: 81 patterns, 85
  values, 4,000 seeded doubles; the four backends print it but for the refused types
  and `1e23` / `2e23`) and 87 more patterns asked of the JDK alone:
  `''` is a quote everywhere; an argument's index, type and style are split at the first
  two commas no quote holds, a brace nests, a space opening the type is dropped; the
  index is `Integer.parseInt` of the text as written (`{+1}`, `{-0}`, `{01}`), refused
  as `can't parse argument number: `, `negative argument number: ` or `N exceeds the
  ArgumentIndex implementation limit` (10,000); a type is `trim`med and compared
  ignoring case -- a JDK's (`number`, `date`, `time`, `choice`, `list`, `dtf_date` ..,
  the 15 `DateTimeFormatter` constants) is an `UnsupportedOperationException` by name
  when the pattern is read, any other `unknown format type: ` with the text from its
  first non-space; `toPattern` quotes each run of `{` in one quote and doubles a quote.
  An index past the arguments (or a null array) prints `{n}`. A number with no type
  is what a JDK's English `NumberFormat` writes: an `Integer`, `Long`, `Short`, `Byte`
  or atomic its long grouped by three; any other `Number` its `doubleValue` rounded
  half to even at three digits after the point, read from `Double.toString`'s shortest
  digits, a tie in them decided by the double's exact value (`m * 625` against the
  digits `<< (j - 4)`, a tie having exactly four decimals); from 2^53 to 2^63 a JDK
  prints the whole number's own digits with the last 0 (to 2^58), 1 (to 2^61) or 2
  rounded away (a probe of 20,000 values per exponent, no exception); `NaN`, `\u221E`;
  `-0` for a negative that rounds to zero; a null locale throws only for a number.
  Measured against a JDK's `NumberFormat`: the rule is exact below 2^63 (200,000
  values); at 2^63 and above a JDK's digits come from an older algorithm and differ
  from the shortest in 1 of 100,000 random doubles, 6 of 2,602 `d*10^p` (`1e23`) and 173
  of 7,671 `d*2^p` (`deviations.md`). A refused `applyPattern` keeps the old pattern (a
  JDK's keeps a mix). Cost: a program formatting one message is 154,047 -> 175,771 B of
  wasm against `String.format`, 11 KB of it the double path (the long arithmetic, no
  one piece above 400 B; a digit string with `insert` was 5.9 KB more). YAVI's classic
  example with `Normalizer` the one shim left prints the JDK's lines on the four
  backends: wasm 616,785 B, the component 620,879 B, the jar 240,951 B. Pins: java-spec
  `a-message-format-puts-its-arguments-in-a-pattern`;
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (a format
  type). A `BigInteger` and a `BigDecimal` are written from their own digits since k31
  ("java.math" below).
- **`java.text.Normalizer` (k29, 2026-11-19)** is the enum `Form` and two statics of
  Java source over one `native`, `normalize0(text, compatibility, composed)`, which is
  `%unorm-normalize` of the library the front ends share
  ([unicode-normalization.md](unicode-normalization.md): the algorithm, the tables, the
  cost). `isNormalized` is `normalize` compared; a null text or form is the
  `NullPointerException` of the call made on it, the text's first. With it YAVI's
  classic example compiles from the unpatched coordinates
  (`--java-source-dep am.ik.yavi:yavi:0.18.0`) and prints a JDK's lines on the four
  backends: wasm 655,275 B, the component 659,541 B, the jar 255,602 B. YAVI was not
  changed: the scan below the form's first code point makes its call per string
  checked the cost of a `charAt` loop over it. Pin: java-spec
  `a-normalizer-puts-a-text-in-the-four-forms`.
- **`java.util.UUID` (k36, 2026-11-20)** is Java source: two longs, `fromString`,
  `toString`, `equals`, `hashCode`, `compareTo` (signed, as specified), the two halves,
  `version`, `variant`, `randomUUID` (version 4 over a `Random()` of its own, made at the
  first call). Learned from a JDK 25's answers, since the specification gives only the
  canonical form: a text of more than 36 chars is `UUID string too large`; one without
  exactly four dashes is `Invalid UUID string: ..`; the five parts are read in order, each
  as a signed hexadecimal long (a leading `+`, a digit one char of Unicode, no `0x`, at
  most `7fffffffffffffff`) and cut to its field (32, 16, 16, 16, 48 bits), so `1-1-1-1-1`
  and `100000000-1-1-1-1` are UUIDs; a part that is empty, has a char that is no digit or
  passes a long is a `NumberFormatException` with the words of `Long.parseLong` of a range
  (`Error at index N in: "part"`, the index of the char refused or of the digit that
  overflows). `Character.digit` reads the digits, so a digit outside ASCII follows its
  deviation (`deviations.md`). Refused by name: `nameUUIDFromBytes` (MD5),
  `timestamp`, `clockSequence`, `node`. YAVI's `UntypedInput` table keeps its `UUID` entry
  (`patch.sh` lost its second edit): the schema example compiles and prints a JDK's
  lines on the four backends with four edits left, wasm 1,019,971 B, the component
  1,024,234 B, the jar 385,127 B; a validator reading a string as a `UUID` through
  `untyped(UUID.class)` answers a JDK's lines on the interpreter. Pin: java-spec
  `a-uuid-is-two-longs-read-from-its-text-as-a-long-is`.
- **The members j98 left that a line of Java answers (k05)**: `Math.divideExact` /
  `floorDivExact` / `ceilDivExact`, `String.codePointCount` / `offsetByCodePoints` (a
  string's elements are code points, so a range holds as many as it is long) /
  `indexOf` within a range, `Character.toChars` into an array (the low surrogate
  first, so an array too short keeps the high one's place) / `codePointAt` of an array
  / `reverseBytes`, `OptionalLong` and `HttpHeaders.firstValueAsLong`: each in
  `Support` or a source class, 866 probe lines identical to JDK 25 on four backends
  (`Members.java` beside `Dur.java`). Still refused: `.todo/k82`.

## The sorted collections (2026-11-20, k47)

**Invariant: a `TreeMap` is a tree balanced as AVL trees are; what a program sees of it --
the order, every answer and refusal of the map and of its views, what each spliterator
reports -- is a JDK 25's, learned from probes; which pairs of keys its comparator is asked
about is the tree's own.** Written from the API documentation and JDK 25's answers
([java-frontend-provenance.md](java-frontend-provenance.md)); the probes are
`.todo/artefacts/k47-java-front-end-what-the-stream-members-slice-left/`.

- **The tree** (`TreeMap.Entry` is the node, with its height; `rebalance` walks up from the
  changed node and stops where a height is what it was). A node with two children gives
  its place to the next node by relinking, never by taking its key, so an entry a program
  holds and the node an iterator goes to next stay valid through a removal. A put into an
  empty map compares the key with itself and `get` / `containsKey` / `remove` under the
  keys' own order cast the key first: the JDK's refusals of `null` and of a key with no
  order on an empty map. A copy of a sorted map of the same comparator, `putAll` of one
  into an empty map and `addAll` of a sorted set into an empty `TreeSet` build the tree
  from the ordered items with no comparison, as a JDK's do (`Calls.java`).
- **A view is the tree and its bounds** (`TreeMap.View`, one class for `subMap`, `headMap`,
  `tailMap` and `descendingMap`: a low and a high bound, each there or not and holding its
  key or not, and the descending flag). Navigation clamps the tree's `near` (lower, floor,
  ceiling, higher) to the range; a walk runs from the range's first node to its fence, the
  first node past it, found once. The refusals and their order are a JDK's (`Bounds.java`:
  13 views, every bound by both flags): `fromKey > toKey` where a range ends before it
  starts, the bounds named in the view's own order; of a view of a view `fromKey out of
  range`, then `toKey out of range`, where a bound holding its key must be inside the range
  and one leaving it out may sit on the range's edge; `key out of range` for a put.
- **`TreeSet`** is over a `NavigableMap<E, Object>`, a `TreeMap` or a view (a view of a set
  is a `TreeSet` over the map's view, as a JDK's); `TreeMap.KeySet` extends it and refuses
  `add`. A JDK keeps one `keySet`, `entrySet` and `descendingMap` of a map (`==` answers
  `true`); here each call makes one, as `HashMap`'s do (`deviations.md`): three fields in
  every `TreeMap` for an identity no probe compared.
- **Spliterators** (`SplitGrid.java`, 249 lines: the tree, a comparator, the skip list and
  the wrappers by eight views): the tree's keys `ORDERED|DISTINCT|SORTED|SIZED` with its
  comparator, its values `ORDERED|SIZED`, its entries those of its keys with a comparator of
  the keys; the keys of the descending map of the tree itself (of no other view)
  `ORDERED|DISTINCT|SIZED`; any other view's keys `ORDERED|DISTINCT|SORTED`, or
  `ORDERED|DISTINCT` against the order, of unknown size, its values and entries the
  defaults of `Collection` and `Set`. A size is taken when first asked and kept.
  `Splits.sorted` carries the comparator.
- **`ConcurrentSkipListMap` is a `TreeMap`, or a view of one, under the map's monitor**
  (`java.util.concurrent.Ordered`: the map and its `SubMap` views), as `ConcurrentHashMap`
  is a table under one; there is no skip list. No `null` key or value; a function of
  `compute`, `merge` and their kind runs once under the monitor. Its views refuse as a
  JDK's do, which is not as a `TreeMap`'s: a bound below the low bound or above the high
  one is `key out of range`, a range ending before it starts `inconsistent range`, and a
  bound holding its key may sit on an edge the range leaves out (`(3,7).headMap(3, true)`
  is empty, a `TreeMap`'s `toKey out of range`). An iterator holds the entry it read ahead
  and reads the next by its key (`higherEntry`), so it never refuses; a JDK's follows a
  removed node's link, so a key put between the two after such a removal is given here
  and passed by there (`deviations.md`; the corpus case keeps clear of it). Spliterators:
  the map's keys `ORDERED|DISTINCT|SORTED|NONNULL|CONCURRENT` of the count, its values
  `ORDERED|NONNULL|CONCURRENT`, its entries as its keys; a view's keys
  `ORDERED|DISTINCT|SORTED` with the view's comparator (reversed against the order), its
  values none, its entries `DISTINCT`, of unknown size. `ConcurrentSkipListSet` is over
  one; its views are sets of its class.
- **The wrappers**: `unmodifiable`, `synchronized` and `empty` of the four sorted
  interfaces; a synchronized map holds a lock its views share (`Synchronized.MapOf`). An
  unmodifiable navigable map answers `SimpleImmutableEntry`s. **Measured and not made**: a
  JDK's unmodifiable map entry refuses `setValue`, and an unmodifiable view's iterator
  `remove`, with no message, where these say `not supported` and `remove`; the JDK's
  words were 2,282 B of wasm in a program walking an unmodifiable map's entries (a second
  entry class in the dispatchers) and 301 B in one walking an unmodifiable set.
- **What came with them**: `Map.remove(key, value)` and `replace(key, old, new)` as the
  documentation states their defaults (`ConcurrentMap` declares them again),
  `Map.Entry.comparingByKey` / `comparingByValue` of a comparator, `SortedMap`'s
  `firstEntry` .. `pollLastEntry`, `putFirst` and `putLast`, `SortedSet`'s `getFirst` ..
  `addLast`, and `AbstractSet.removeAll` as the documentation states it (the smaller of
  the two is walked, so a set under a case-insensitive order loses `A` to
  `removeAll(List.of("a"))`). `SequencedCollection`, `SequencedSet`, `SequencedMap`, the
  `sequenced*` views and `reversed()` of a `SortedMap` that is no `NavigableMap` came with
  k55 ("The sequenced collections"); `clone()` is `.todo/k16`'s.
- **Measured** (2026-11-20; wasm bytes): a program naming none of them is unchanged to the
  byte (the size programs of k12 and k26); a word count into a `TreeMap` 136,456, into a
  `HashMap` 143,443; a `TreeSet` asked `first` / `ceiling` / `headSet` 142,364; the
  navigation and views of a map 140,539; a `ConcurrentSkipListMap` 164,996; `removeAll` on
  a `HashSet` 156,880 -> 157,547. 200,000 puts and gets, a walk and 100,000 removes, best of
  three: JDK 25 196 ms (a `HashMap` 46), the JVM class 369 (209), precompiled wasm 739
  (270). A seeded run of 24,000 operations over maps, views, sets and iterators
  (`Stress.java`) and 160,000 over a tree of up to 54,415 keys (`Grow.java`) print a JDK's
  lines on the four backends.
- Pins: java-spec `a-tree-map-keeps-its-keys-in-order-and-a-view-is-a-range-of-them` and the
  sorted rows of `a-spliterator-reports-what-a-jdk-reports-of-each-collection-and-a-stream-is-made-of-one`.

## The enum collections (2026-11-20, k47)

`EnumSet` is a `long` word for each 64 constants of its enum, a bit by ordinal, over the
constants `Class.getEnumConstants()` answers (a value since k30: "A class as a value");
`RegularEnumSet` and `JumboEnumSet`, the classes a JDK names a set of up to and of more
than 64 constants by, are empty subclasses. `EnumMap` is a slot for each constant (the
value and whether there is one). What a JDK 25 answered beside the documentation, all
lines of the corpus (`Enums*.java` of the k47 artefacts): an iterator of a set reads a word
when it comes to it (a constant removed after that is still given, one added is not); a
map's iterator reads the slots as they are and never refuses; a map's entry is its slot,
`IllegalStateException` `Entry was removed` once its iterator removed it, and set again
after its key left the map it holds the value without the map's count (a JDK's `size()`
goes on counting one less, down to `-1`); `range` refuses `from > to` in those words,
`copyOf` an empty collection `Collection is empty`, a copy of an empty map that is no
`EnumMap` `Specified map is empty`, a constant of another enum `class A != class B`. Their
hash codes are their constants' identity numbers ("The decisions"). Sizes (wasm bytes): a
set made, added to and walked 138,652; a map put to, merged and walked 132,973 (a
`HashMap` 145,706). Pin: java-spec `an-enum-set-is-a-bit-of-each-constant-and-an-enum-map-a-slot`.

## The sequenced collections (2026-11-20, k55)

**Invariant: a list, a deque, a linked or sorted set and a linked or sorted map are a
`SequencedCollection`, `SequencedSet` or `SequencedMap`, and `reversed()` is a view of it in
the other order through its own public members; what a JDK 25 answers through such a view --
each index and refusal with its words, what a walk sees of a change -- is the corpus's.**
Written from the API documentation (the defaults' stated implementations) and JDK 25's
answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)); the probes
are `.todo/artefacts/k55-java-front-end-what-the-stream-members-and-the-sorted-collections-left/`.

- **The defaults** (`SequencedCollection`, `SequencedSet`, `SequencedMap`): the first element
  through `iterator()`, the last through `reversed().iterator()`. An entry
  `firstEntry` .. `pollLastEntry` answers is a copy, `ImmutableCollections.Pair` (a null
  kept, `setValue` saying `not supported`, as a JDK's); `SortedMap`'s defaults answer it too
  (they answered a `SimpleImmutableEntry`, whose refusal has no message, so
  `synchronizedSortedMap(..).firstEntry()` was not a JDK's). `sequencedKeySet`, `Values` and
  `EntrySet` are `MapSequence`'s views: each asks the map for its own view at every call (a
  probe counted a JDK's `keySet()` calls), refuses `add` and `addAll`, reaches its ends
  through the map's entries at its ends (`getFirst()` of the entries is a copy) and is
  reversed through the reversed map.
- **A list's view** (`ReversedList`, `ReversedList.Indexed` over a `RandomAccess` list, as a
  JDK's view is one): index i is the list's size - 1 - i, checked against the view before the
  list is asked (`Index -1 out of bounds for length 6`, `Index: 9, Size: 8` for an `add`,
  `Range [2, 1) out of bounds for length 10` for `subList`); `add` is `add(0, e)`,
  `remove(o)` takes the last equal, `sort` sorts the list by the reverse order; a walk is the
  list's own `listIterator` walked back (`Backward`, whose `add` steps back over what it
  added), so a change behind it is the list's `ConcurrentModificationException`; `removeIf`,
  `replaceAll` and `clear` are the list's; `removeAll` and `retainAll` walk the view (k60). The
  view of an `ImmutableCollections.ListN` refuses every change at once, as the list does (a
  JDK's of `List.of`).
  `LinkedList.reversed()` is a `LinkedList` (`LinkedList.Reversed`, which holds no cell:
  a member with an index is the list's view's, one with an end the list's of the other end).
- **A deque's view** (`ReversedDeque`): every member calls the deque's of the other end
  (`push` is `addLast`), so a bound's `Deque full` and a refused null are the deque's;
  `remove(o)` is `removeLastOccurrence`, which `Deque` declares now with
  `removeFirstOccurrence` (`ArrayDeque`, `ConcurrentLinkedDeque` refusing a null as
  documented, `LinkedBlockingDeque`). Its spliterator is `ORDERED|SIZED|SUBSIZED`, the
  estimate kept.
- **`LinkedHashMap`, `LinkedHashSet`**: `putFirst` / `putLast` (`addFirst` / `addLast`) move
  a key the map holds to that end -- a change unless it is there already, in access order
  too -- and a key added is put, moved, then asked about as eldest, so `putFirst` of a new
  key into a full LRU map removes that key (a JDK's answer). `firstEntry` ..
  `pollLastEntry` read the chain's ends. The reversed map (`LinkedHashMap.Reversed`) reaches
  a key by the map's own member (in access order a `get` through it moves the key), puts a
  new key at the map's end, walks the chain back (`Backward`; `KeysBack`, `ValuesBack`,
  `EntriesBack` subclass the forward views) and has the defaults' `sequenced*` views, as a
  JDK's has. A set's view adds at the set's end; its spliterator is `DISTINCT|SIZED|SUBSIZED`
  (a JDK's has no `ORDERED`). A program of a `LinkedHashMap` calling none of them is
  unchanged to the byte (`SzLinked`).
- **A sorted set or map that is not navigable** (`ReversedSortedSet`, `ReversedSortedMap`:
  `unmodifiableSortedSet(..).reversed()`) is walked live, each next element the last of the
  head before the one given, so a walk sees what was added ahead of it (`3210`); a range is
  a first element and one it stops before, null where there is none (a JDK's `headSet(null)`
  is the whole set). The refusals are a JDK's, read off a grid of every bound of three
  ranges (`Seq3.java`): a bound of a range must be in it (`IllegalArgumentException`, no
  message), a set's range of the whole view may end before it starts and is empty where a
  map's refuses, and -- a JDK's own -- `tailSet` of a range keeps only the end it stops
  before (`headSet(3).tailSet(4)` is `[5, 4]`; the map's is a range of both ends). An entry
  of the map's view sets its value through `put`.
- **`Collections`**: `unmodifiableSequencedCollection` (equal only to itself, as
  `unmodifiableCollection` now is), `unmodifiableSequencedSet`, `unmodifiableSequencedMap`
  (each reversed over the reversed held one), `newSetFromMap`, `newSequencedSetFromMap`
  (`Map is non-empty`). `HashMap.newHashMap` and its three kin ask for
  `(int) Math.ceil(n / 0.75)`: a JDK's table order at 6 to 97 mappings.
- **Fixed where the probes met them**: `AbstractList.subList`'s refusals are a JDK's
  (`fromIndex = -1`, `toIndex = 5`, `IllegalArgumentException: fromIndex(2) > toIndex(1)`;
  a `CopyOnWriteArrayList`'s an `IndexOutOfBoundsException` with no message);
  `Collections.reverseOrder(null)` is the reverse of the elements' order (it threw where it
  compared); an unmodifiable collection or map refuses `removeIf`, `compute` and their kind
  even where nothing would change; `unmodifiableCollection` is equal only to itself and an
  unmodifiable entry set to what the map's entries are equal to (`Map.of(..).entrySet()`
  equalled no set); `CopyOnWriteArrayList.removeAll` / `retainAll` (they threw); the
  iterators of `ConcurrentLinkedQueue`, `ConcurrentLinkedDeque` and the blocking queues
  removed from their copy, so `removeIf`, `removeAll`, `retainAll` and `iterator().remove()`
  answered `true` and changed nothing (`java.util.concurrent.Copied`: the element at its
  place, else the first equal one, `deviations.md`). Each is a line of the corpus case below
  that the jar before answered otherwise (`Fixes.java`).
- **Measured** (wasm bytes, the jar of 5303feb71 against this one): the size programs of
  k12, k26 and k47 are unchanged to the byte but those over an unmodifiable view, whose
  equality moved (`SzUnmod` 175,139 -> 175,170, `SzUnmodMap` 163,860 -> 163,606,
  `SzUnmodSet` 104,850 -> 104,824); a list printed reversed (`SzReversed`) 133,376 ->
  143,015, the view in place of a copy; `putFirst` and the reversed map 153,334, a deque's
  view 133,784; iterating a `ConcurrentLinkedQueue` 63,954 -> 66,243, the iterator that
  removes from the queue (a second iterator class: the loop's calls are dispatched). A
  program of one list runs in the same time on the interpreter (1.42 to 1.50 s against 1.47
  to 1.58 s, five runs each).
- Pins: java-spec `a-sequenced-collection-has-two-ends-and-a-reversed-view` (with the fixes
  above); `JvmLispCompilerTest#aCycleTheRootDoesNotCloseDispatchesOnAMemberIndex` (the view's
  `next` and `previous` call each other's, which made a JVM tail group of members of two
  arities: [jvm-self-tail-calls.md](jvm-self-tail-calls.md)).

## The collections of Collections, a bridge's cast and PriorityQueue (2026-11-20, k60)

**Invariant: the collections of no element, of one and of n copies of `Collections` are
classes of their own over the abstract collections, so a change is refused where it would
change something and answered where it would not; the list of `List.of` refuses every change
and a null asked for; an arm of a dispatcher whose body takes a narrower type than the method
called tests the argument as a JDK's bridge method casts it; `PriorityQueue` is a binary
heap whose order is a JDK's.** Written
from the API documentation and JDK 25's answers to probes
([java-frontend-provenance.md](java-frontend-provenance.md)); the probes are
`.todo/artefacts/k60-java-front-end-what-k55-left/`.

- **`Collections`**: `EmptyList`, `SingletonList`, `CopiesList`, `EmptySet`, `SingletonSet`,
  `EmptyMap`, `SingletonMap` (`singletonMap` new), the empty iterators and enumeration
  (`emptyIterator`, `emptyListIterator`, `emptyEnumeration`; `Enumeration.asIterator`); the
  empty ones are one object each. What each refuses at once and with which words is a JDK's
  answer (`Imm.java`, 1,406 lines over 22 collections): `singletonList`'s and `singleton`'s
  `removeIf` and the iterator of one element refuse at once, `emptyList`'s `get` says
  `Index: 0`, `singletonList`'s `Index: 1, Size: 1`, `nCopies`' is `checkIndex`'s, the views
  of a map of one mapping are sets of one element (`values()` too). Their reversed views are
  the changing ones (a JDK's are). Each has only the members whose answers are not its
  abstract class's, and what several answer alike is a member of a class above them
  (`Few`, `FewSet`, `FewMap`), one arm of a dispatcher: the five classes' spliterators as
  five arms were 1.3 KB of YAVI's schema example, an empty list walked by `AbstractList`'s
  iterator 1.3 KB of a program of none other.
- **The defaults check for null as specified**: `Iterable.forEach`, `Collection.removeIf`,
  `List.replaceAll`, `Map.forEach` / `replaceAll`, `AbstractCollection.removeAll` /
  `retainAll`; `AbstractList.addAll(int, ..)` checks its index first. `Objects.requireNonNull(o)`
  is `native`, `(or o (%java-npe))`: the run time's `NullPointerException`, so no exception
  object is made where nothing is null.
- **`List.of`'s list** (`ListN`): `get` is its array's read, the JDK's
  `ArrayIndexOutOfBoundsException` for 0 and 3+ elements (577 -> 240 B of wasm; the words of
  a list of one or two were 440 B more in every `List.of` program and are not made); a null
  asked for is refused, and so are `addAll(int, ..)`, `removeIf`, `replaceAll`,
  `removeFirst` / `removeLast` at once; `subList` is a `SubList`, a copy refusing an index
  with `checkIndex`'s words. `Unchanging` and `ListView` refuse `addAll` at once,
  `ListView.subList` is a `ListView`.
- **Reversed views**: `removeAll` / `retainAll` walk the view and remove through its
  iterator, as a JDK's do (through `Arrays.asList`, `unmodifiableList`); `removeFirst` /
  `removeLast` of an empty view refuse before a change. `CopyOnWriteArrayList.reversed()` is
  a view of its own handing the list the index of the other end unchecked (`get(-1)` of six:
  `Index 6 out of bounds for length 6`), its walk the snapshot backward. `Arrays.asList`'s
  list is named `Arrays.ArrayList`.
- **A bridge's cast**: an arm whose body takes a parameter narrower, erased, than the method
  called (`Integer.compareTo(Integer)` for `Comparable.compareTo(Object)`, a program's
  `compareTo(Version)`, `JavaLowering.bridged`) tests it: `null` or an instance passes,
  anything else is `%cast-refused`, one function of the program raising the
  `ClassCastException` of a failed cast. A call going straight to its one body tests
  nothing, nor a lambda. Measured (wasm bytes): a program sorting strings 157,884 -> 158,398
  (three arms), a `TreeSet` 142,377 -> 142,876, navigation 140,531 -> 141,065. A
  dispatcher made for the one body too was 2.6 KB in a program putting into an `EnumMap`
  through `Map` (the class names of any value it then needs): not made.
- **`PriorityQueue`** (and `AbstractQueue`, as the specification states its members): a
  binary heap in an array (Williams 1964; a collection in no order made a heap by Floyd's
  1964 sinking, and so are the survivors of `removeIf` / `removeAll` / `retainAll`, kept in
  their order). Which of equal elements goes where, which pairs the comparator is asked
  about and the order of a walk that removes are a JDK's answers: an element climbs while it
  is less than its parent, sinks below the lesser child (the left of two equal) while it is
  greater; a removal puts the last element in the hole, sinking it or else climbing it, and
  one that climbed behind a walk is given after the array, in the order moved (`PQOrder`,
  60,000 steps; `PQWalk`, 2,000 walks; `PQSeeded`, a 48,400-line transcript, every line a
  JDK's on the four backends). A walk checks the changes at `next` and `remove`, `hasNext`
  not; `forEach` and a bulk removal ask every element of the array there was and then refuse
  a change made meanwhile (`removeIf` of nothing is a change, a JDK's answer). With no
  comparator an element added is cast to `Comparable` first, the first one too; one of a
  collection is not (a queue of one `Object` is made). Its spliterator is
  `SIZED|SUBSIZED|NONNULL` and does not split. Sizes: a program adding and polling 138,238 B
  of wasm; 1,000,000 adds and polls of `Integer`s 5.0 s on wasm, 3.7 s as a JVM class, 0.57
  s under a JDK.
- **Measured** (wasm bytes; the size programs of k12, k26, k47 and k55): unchanged but those
  above and a program over `List.of`, smaller (`MapOnly` 137,639 -> 137,302, `FlatOnly`
  127,939 -> 126,933, `SzRemoveAll` 157,547 -> 156,534, `SzUnmodSet` 104,824 -> 104,124).
  YAVI's examples: `Member.java` 663,174 -> 662,353, `Booking.java` 1,204,473 -> 1,208,802
  (`singletonList`'s class, the bridged arms of its own validators, `Iterable.forEach`
  with a null check). Measured and not made: a null asked of `Set.of` and `Map.of` refused
  (some 300 B in a program asking them), `Map.of`'s keys and values as `AbstractMap`'s views
  (500 B), `Arrays.asList`'s iterator of its own for the words `remove` (0.9 to 1.4 KB in a
  program walking it and another list).
- **Left** (`deviations.md`, `.todo/k82`): a list of `List.of` of one or two refuses an index
  with the array's words; `List.of`'s iterator's `remove()` before `next()` is
  `IllegalStateException`; `Set.of` and `Map.of` answer a null, and `Map.of`'s views refuse a
  change that changes nothing; `Arrays.asList` refuses a removal with no message
  (`Stream.toList()` came with k71: "A raw argument, `Stream.toList()`, the digits and the
  members met" below); the empty `List.of()` is a new object; an `AbstractList`
  iterator past the end has no cause (a `catch` there would be in every program walking such
  a list); a map's views are new at each call, so `values().equals(values())` is `false`.
- Pins: java-spec `a-deque-an-optional-the-utilities-and-the-older-classes` (`immutables()`,
  `priorities()`), `JavaLoweringTest#typeParametersLambdasAndBoxes` (the bridged arms).

## A raw argument, `Stream.toList()`, the digits and the members met (2026-11-21, k71)

**Invariant: a call that applies only by the unchecked conversion of a raw argument is typed
as JLS 18.5.2 and javac type it -- the type it is wanted as says nothing of its type
parameters, its value is the erasure of the type returned -- and warned of as javac warns;
the list of `Stream.toList()` and of a gatherer's window is a list of `List.of`'s kind that
holds nulls; `Character.digit` is one rule on every backend, Unicode's decimal digits read
from a list of their zeros.** Written from the API documentation, the JLS and JDK 25's
answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)); the probes
are `.todo/artefacts/k71-java-front-end-what-k60-left/`.

- **A raw argument** (`JavaLowering.resolve`, `uncheckedApplied`, `bounded`):
  `Collections.max(raw)` was `inferred type does not conform to upper bound(s)` -- the
  target `Object` made `T` an `Object`, which is no `Comparable<? super T>`. Now the target
  is not told and the value is the erasure of the type returned, each type parameter what
  the other arguments found or else the first of its bounds that is not `Object`: javac's
  `Comparable` for `Collections.max(raw)`, `Number` for `T extends Number`, `Integer` for
  `top(raw, 3)`; a creation with `<>` is raw (`new ArrayList<>(raw)` an `ArrayList`), one
  with written type arguments keeps them. Read off javac's refusal of `int x = call(raw)`,
  which names the type (`Types.java`). `Collections.max` / `min` declare `T extends Object &
  Comparable<? super T>`, as the API does (a warning's `where` line names both bounds).
- **Its warnings** (`JavaWarnings`; `Warnings.java`): `unchecked method invocation` names
  the class raw (`in interface List`, no where line for its parameters) and is said where
  the call applies by the conversion; an argument's `unchecked conversion` names the
  parameter as the method declares it (`Collection<? extends E>` through a `List<String>`)
  and is said where the parameter, its type parameters found or at their bounds, still
  needs the conversion. `? extends Object` is `?` (JLS 4.5.1; `reifiable`), so
  `new ArrayList<>(raw)` and `m(raw)` of `<T> m(Collection<? extends T>)` say no conversion
  and `lo.addAll(raw)` of a `List<Object>` nothing at all, as javac. A value's conversion is
  said after what its expression said, whatever the places (`Comparable<String> c =
  max(raw)`: the argument's caret is past the call's; `Warning.order`, `assign(.., since)`).
- **`Stream.toList()` and a window of `Gatherers.windowFixed` / `windowSliding`** are an
  `ImmutableCollections.ListN` of the kind `NULLS` (a fourth `how`: its spliterator is
  `OWN`'s, `indexOf`, `lastIndexOf` and a part take a null). The streams' classes cannot
  name a class of `java.util` that is not public (javac compiles the library as
  `java.base`), so `Flow.listed(Object[])` is a `native` member whose form calls
  `ImmutableCollections.listed` (`JavaIntrinsics.NATIVES`, `lowering.library`): no member a
  program could name. k60's `Imm.java`: 125 -> 99 lines differ from a JDK, the rest
  `List.of`'s own.
- **`Character.digit`** (`%java-digit`): ASCII through `digit-char-p` of base 36, the
  fullwidth Latin letters as the ASCII ones, a decimal digit of Unicode (category Nd) as
  its distance from the zero of its set of ten, the 75 zeros above ASCII a quoted list
  (`%java-unicode-digit`, pinned to the running JDK by `JavaLoweringTest#theLoweringTable`).
  The host's `digit-char-p` answered until then: Unicode's digits on the interpreter and
  the JVM, none on wasm. A number's text (`Double.parseDouble`) and a format's control read
  ASCII digits only (`%java-ascii-digit`), as a JDK does (the interpreter read `"１２"` as
  12.0). A sweep of every code point (`Sweep.java`) gives a JDK's `digit` of radix 10 and 36
  and `toTitleCase` on the JVM and wasm. Measured for the table (wasm bytes): the list 670
  and its scan 590, +1,538 in a program parsing an int; a string of bounds decoded into a
  vector was 3,166, a vector literal +3.8 KB and a string of the zeros as characters +2.6 KB
  in a small Lisp program. **Not made**: `getNumericValue` of the numbers that are no digit
  (`Ⅷ`, `²`, `½`; 1,159 code points in some 520 runs, 4 KB as text) -- in every program
  calling it, and programs call it for ASCII digits.
- **Members**: `String.stripIndent`, `translateEscapes` (`Support`; the refusal's words a
  JDK's, U+0000 named after a final backslash), `Character.toTitleCase` (the uppercase, but
  for the digraphs `Ǆ` .. `ǌ`, `Ǳ` .. `ǳ`, and Georgian's Mkhedruli, which has none),
  `codePointAt` and `codePointCount` of a `CharSequence` (`named` / `meets`). Probes:
  `Strip.java` (33 lines), `Title.java`.
- **Measured** (wasm bytes, the size programs of k12, k26, k47, k55, k60 and k62 and four
  more, `sizes/` of the artefacts): unchanged but `SzParse` 58,309 -> 59,847 and `FoBig`
  189,254 -> 190,252 (the digits), `Fo` and `SzFormat` -590 and `SzDouble` -437 (no
  `digit-char-p`), `SzToList` -901, `MapOnly` -709, `MultiOnly` -707, `GatherOnly` -504 (a
  `ListN` for an unmodifiable view), `SzUnmod` +124, `SzUnmodSet` +212, `SzRemoveAll` +119,
  `SzSub` +96 (the `NULLS` test). YAVI's `Member.java` 643,806 -> 645,561, `Booking.java`
  1,118,067 -> 1,119,947 (the digits).
- Pins: java-spec `the-unchecked-warnings-are-printed-before-the-program-runs` (a raw
  argument), `a-deque-an-optional-the-utilities-and-the-older-classes` (`toList()`'s list),
  `a-gatherer-is-a-stage-holding-what-it-pushes-until-it-is-asked` (a window),
  `the-boxes-parse-print-and-compare-and-math-is-strict` (the digits),
  `a-string-has-its-everyday-surface-and-two-constants-of-a-text-are-one` (the members);
  `JavaLoweringTest#theLoweringTable` (the zeros).

## A joiner, a tokenizer, a bit set, a scanner and a char buffer (2026-11-21, k72)

**Invariant: `StringJoiner`, `StringTokenizer`, `BitSet`, `Scanner`, `Readable`, `Buffer` and
`CharBuffer` are Java source answering what a JDK 25 answers -- each refusal's words, `BitSet`'s
`size()`, when a `Scanner` reads more of its input and what its text says -- and a `Scanner`
whose program names no pattern holds no regular expression.** Written from the API
documentation and JDK 25's answers to probes
([java-frontend-provenance.md](java-frontend-provenance.md)); the probes are
`.todo/artefacts/k72-java-library-the-everyday-classes-j71-left/probes/` (each run on the four
backends by the `four.sh` above them, the JDK's lines in `*.jdk.txt`).

- **`StringJoiner`**: the elements so far are one text without the prefix and suffix; `add`
  takes `String.valueOf` of the element (a `CharSequence`'s `toString`, not its characters);
  a merge appends the other's elements as one, read before anything is appended, so a joiner
  merged with itself doubles. Read off the answers: the null refused first is the prefix's,
  then the delimiter's, then the suffix's, each with its words (`Joiner.java`, `Joiner2.java`).
- **`StringTokenizer`**: a position and the delimiters, the token found from the position
  each time, so `hasMoreTokens` and `countTokens` move nothing; `nextToken(String)` changes the
  set first and keeps it. Read off the answers: a null set is refused by `hasMoreTokens` and
  `nextToken` at once, by `countTokens` only where it has a character to look at
  (`Tokens.java`, 130 lines).
- **`BitSet`**: words of 64 bits and the count of words in use; `hashCode` is the
  specification's formula. What `size()` answers was read off the answers (`BitSize.java`,
  `BitSize2.java`): a set of no size given holds one word, one of `n` bits `ceil(n / 64)`,
  which a clone keeps until the set grows; growing takes twice the words or the words needed,
  whichever is more; a clone of any other set, and a set of `valueOf`, holds the words in use;
  `get(from, to)` is a set given `min(to, length()) - from` bits. `stream()` is
  `IntFlow.over` with the cardinality as its count, so its spliterator is the subset's
  (`ORDERED|SIZED|SUBSIZED`, no split; `deviations.md`); a JDK's reports `DISTINCT|SORTED` and
  splits at the middle of the set bits' span (`BitSplit.java`, not followed). `clone()` is a
  public member: the subset has no `Cloneable`.
- **`Scanner`, its buffer**: the input read so far is a `String` and the position an index in
  it; a `String` source is its whole text, a `Reader` is read in pieces of `max(1024, what is
  held)` characters and what is behind the position dropped, a high surrogate a piece ends in
  held for the next. A `Readable` that is no `Reader` is read through a `CharBuffer` of the
  piece's size (`Scanner.Readables`), so a program reading a `String` or `System.in` holds no
  `CharBuffer`. The positions `match()` and `toString()` give are in that string, a JDK's in
  its own buffer (the same up to 1024 characters; `deviations.md`).
- **`Scanner`, the delimiter is no pattern by default**: `Character.isWhitespace` is what
  `\p{javaWhitespace}` means, so the default tokens are cut by code (the ASCII ones decided
  inline) and `delimiter()` compiles the pattern only when asked. The members taking a
  pattern's text (`hasNext`, `next`, `useDelimiter`, `findInLine`, `findWithinHorizon`,
  `skip`, `findAll`) are `native`, lowered where they are called with the pattern compiled
  there with the providers its text may need (`JavaIntrinsics.scanning`), as `Pattern.compile`
  is; `findInLine(String)` and `findWithinHorizon(String, int)` may answer `null`
  (`JavaIntrinsics.NULLABLE_NATIVES`, `JavaClasses.Library.answersNull`), which a
  concatenation writes. Every search has transparent, non-anchoring bounds (`Scan8.java`).
- **`Scanner`, when it reads more** (`ScanReads.java`: a `Readable` handing scripted pieces,
  24 runs, and the `source closed` of `toString` in `Scan3.java`): a token's end is a
  delimiter found unless the match `requireEnd`s, and none found reads until the end; the
  delimiters before a token read more where their match hit the end, or nothing follows them;
  a line reads more where no separator is in hand or a `\r` ends what is held; `skip` reads
  more where its match hit the end and, failing, to the end of the input; `findWithinHorizon`
  where a match hit the end or none was found while the horizon reaches past what is held;
  `findInLine` to the separator or the end. The fast path reads where `\p{javaWhitespace}+`
  would have.
- **`Scanner`, a number**: the grammar of the class documentation written as code
  (`numeral`, `integral`, `decimal`, `floating`), each kind's parse in its own members so that
  a program holds the kinds it asks for alone. Read off the answers (`Scan1.java` .. `Scan9.java`,
  `Scan7.java`'s 218 lines of radixes): any decimal digit (`Character.isDigit`) is a digit of
  every radix, so a digit past the radix fails the parse, whose words the refusal has (`For
  input string: "9" under radix 8`), where a token the grammar refuses is refused with none; a
  grouped numeral starts with a decimal digit other than an ASCII `0` (a non-ASCII zero
  passes), its other digits any of the radix; the locale's infinity `\u221e` is taken and the
  parse refuses it with the token; a hexadecimal float has a point. A token's commas are
  dropped and an integer's digits handed to the parse as they are (its refusal shows them); a
  float's digits are made ASCII. A sign and at most 18 ASCII digits of radix 10 are read with
  no grammar and no parse. A refused token stays, the position past the delimiters
  (`Scan4.java`).
- **`Scanner`, what a `hasNext` keeps** (`Scan5.java`, `Scan6.java`, 59 lines with the
  delimiter changed between): a number for the next of its kind (its radix compared for the
  integers), kept through `useDelimiter`, `useLocale`, `useRadix`, `hasNext()` and a `hasNext`
  that fails, dropped by every `next`, `nextLine`, `hasNextLine`, `findInLine`,
  `findWithinHorizon`, `skip` and `reset`; `hasNext(Pattern)` keeps the token for `next` of
  that pattern -- by identity, or by text for the members taking a text; where a token ends is
  one slot both share, as a JDK's.
- **`Scanner`, the rest**: `match()` after a token is its span with no group, after a line the
  line with its separator as group 1, after a search the pattern's match, a snapshot; a JDK's
  after a number has the groups of its own pattern (35 for an `int`, 132 for a `double`:
  `deviations.md`). `tokens()` and `findAll` are `ObjectFlow.over` an iterator refusing with
  `ConcurrentModificationException` once the program asked the scanner anything else, their
  `close` closes the scanner; `findAll` searches a character further after an empty match.
  `toString()` is a JDK's text with the English separators. `close()` closes the source.
- **`System.in` closed is at its end** (`StandardInput.close`): a `Scanner` made over it again
  has no token, as under a JDK, whose read throws `IOException("Stream closed")`. Throwing it
  was measured and not made: a program reading `System.in.read()` and throwing nothing else,
  21,472 -> 53,180 B of wasm (the exception classes); at its end, 21,588.
- **`Readable`, `Buffer`, `CharBuffer`**: `Reader` is a `Readable` (`read(CharBuffer)` through
  an array). One class for every buffer: an array of UTF-16 units from an offset, writable or
  read-only, or a text: a `String` wrapped as its units with no array to hand out
  (`UnsupportedOperationException`), any other `CharSequence` read live through `charAt`. Read
  off the answers (`Chars.java`, 59 lines): the words of each refusal (`capacity < 0: (-1 <
  0)`, `newPosition > limit: (9 > 8)`, the range ones of `checkFromIndexSize`, none for the
  others) and `hashCode`, 1 then 31 times the hash plus each element from the last back.
  `order()` is refused by name: the platform's, and `ByteOrder` is not there.
- **Hexadecimal floats in `Double.parseDouble` and `Float.parseFloat`** (`%java-read-number`
  reads radix 16, its `p` exponent required): the `Scanner` hands its hexadecimal tokens to
  them. The same change measures a number's size from its first digit that is not `0`: `0`
  times 500 then `1` was `Infinity`, and so was `0.` with 450 zeros and `1e450`
  (`DecGrid.java`, 8,000 seeded decimal texts, the old reader differing; `HexGrid.java`,
  6,000 seeded hexadecimal texts and 33 at the formats' ends: a JDK's bits on the four
  backends). Cost: a program parsing a double 75,259 -> 76,775 B of wasm; a first draft with a
  function of its own for radix 16 was +5,169 B, one over `(expt base power)` +13,247 B
  (the generic `expt` brings the complex numbers).
- **Measured** (wasm bytes, the size programs of k12, k26, k47, k55, k62 and k71, `sz.sh` of
  k71): unchanged but `SzDouble` above; readers of `System.in` `ReadBytes` 21,472 -> 21,588,
  `ReadChars` 86,373 -> 86,568, `ReadLines` 103,510 -> 103,803 (the closed flag). The new
  classes' programs (`bench/` of the artefacts): a sieve on a `BitSet` 99,516, a tokenizer
  50,211, a scanner of lines over `System.in` 103,351, of a million ints 146,358 (265,496 when
  one member parsed every kind: `java.math` came with it), splitting by `useDelimiter(",")`
  311,455 (the engine; `Pattern.compile(",")` and a matcher alone 286,623). A million ints from
  `System.in` (10 MB, one run each on the loaded box): a JDK 2.16 s, the JVM class 2.35 s,
  wasm 7.84 s, of which reading the 10 MB through an `InputStreamReader` is 5.63 s
  (`StandardInput` asks the host for a character at a time; `ReadChars.java`);
  `BufferedReader` and `StringTokenizer` 0.95 / 2.81 / 13.62 s.
- **Left**: a `Formatter` of its own `.todo/k85`; files, with `Scanner` of a `File` and a
  `Path`, `.todo/k86`; `java.text` `.todo/k87`; the other buffers, `CharBuffer.order()` with
  them, `.todo/k88`; reflection and weak references `.todo/k89`; `BigInteger`'s primes
  `.todo/k90`; the standard input on wasm read a character a host call `.todo/k91`; the
  concurrent queues on `PriorityQueue` `.todo/k21`.
- Pins: java-spec `a-scanner-reads-tokens-numbers-and-lines-of-a-text` (tokens and lines over
  each kind of whitespace and separator, seven delimiters, 22 tokens through every kind and
  radix with the refusals' words, a refused token's position, finds and skips with their
  matches and horizons, a search finding nothing concatenated, what `hasNext` keeps, a closed
  scanner, the streams, a `Readable` in pieces, a charset, a failing source, a `CharBuffer`),
  the `standalone:` case
  `a-scanner-reads-the-standard-input` (a contest's input, the closed `System.in`),
  `joiners`, `tokens` and `bits` in `a-deque-an-optional-the-utilities-and-the-older-classes`,
  the hexadecimal and leading-zero parses of `the-boxes-parse-print-and-compare-and-math-is-strict`;
  `JavaLoweringTest#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition` names
  `java.util.Calendar` where it named `Scanner`.

## The sublist and the walks of a list (2026-11-20, k62)

**Invariant: `AbstractList` keeps `protected int modCount`; `ArrayList`, `LinkedList` and
`Vector` count their changes of size in it (they kept a private `changes`); a walk of an
`AbstractList` and a `SubList` of one refuse with `ConcurrentModificationException` once the
count moved behind them, where a JDK's do.** Written from the API documentation and JDK 25's
answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)); the probes
are `.todo/artefacts/k62-java-library-a-sublist-and-an-index-walk-see-no-change-behind-them/`
(`Sub.java`, 130 lines over every member of a part of an `ArrayList`, a `LinkedList`, a
`Vector`, a program's list that counts and one that does not; `Sub2.java`, 164 lines: the
order of an index error and the refusal, the iterators, `ArrayList`'s own count).

- **A walk** (`IndexIterator`, `IndexWalk`) holds the count it saw and compares at `next`,
  `previous`, `remove`, `set` and `add`, re-reading it after its own change (`set` too: a
  program's `set` may count); `hasNext` is `cursor != walkEnd()` (the list's size, a part's own
  length: below), so a removal that leaves the cursor past the end is a refusal at `next` and
  not a silent stop.
- **A part** (`SubList`) keeps the root list (the part of a part has the same one) and the
  count it saw last in its own `modCount`; `size`, `get`, `set`, `add`, `remove`, `iterator`
  and `spliterator` compare it with the root's, after the index check for `get`, `set`,
  `remove` and `add` (`Index 9 out of bounds for length 2`, `Index: 9, Size: 2`: a stale part
  refuses a bad index first, a JDK's order) and before it for `listIterator`; the rest of
  its members go through those. A change through it re-reads the root's count, so a part of
  a part, its parent and its own walks see it and a sibling does not. A program's list that
  never counts is never refused, as under a JDK (a probe ran a part's loop 21 times).
- **`ArrayList`'s count** moves as a JDK's does: `addAll` and `addAll(int, ..)` of nothing,
  `trimToSize`, `ensureCapacity` past the array, `replaceAll` (new: it is the list's own,
  refusing a change its operator makes) and `sort` (refusing one its comparator makes); not
  `set`, `removeIf` and `removeAll` of nothing, `ensureCapacity` within the array. `Vector`'s
  `replaceAll` is `ArrayList`'s.
- **The reversed views**: `ReversedList.subList` is the list's part of the other range,
  reversed (a JDK's; it was a part of the view, which saw no change of the list, and its
  reversed part is not `RandomAccess` either); `LinkedList.Reversed.subList` is the view's
  (`Range [2, 1) out of bounds for length 5`, as a JDK's).
- **Measured** (wasm bytes, the jar of 2b27ce6f7 against this one; `sizes/` of the
  artefacts, with `size.sh` of k26): a program of one `ArrayList` 123,358 -> 123,504, of a
  `LinkedList` 119,199 -> 119,371, of a `List.of` and an unmodifiable view 1,500 B more
  (`MapOnly` 136,846 -> 137,628, `SzUnmod` 170,514 -> 171,086, `FlatOnly` 126,708 ->
  128,183), of a program's own `AbstractList` and a walk 86,870 -> 88,385
  (`ConcurrentModificationException` itself is some 1 KB), with a part `SzSub` 140,055 ->
  141,754, `SzOwn` 90,896 -> 93,493; the sorted and hash maps and `Fo` unchanged. Measured
  and not made then: a `hasNext` that reads the part's own size by `list instanceof SubList`
  in each walk (800 B in `FlatOnly` and `SzRemoveAll`; k74 made it as a method of
  `AbstractList` the part overrides, below), and a part's own iterator as the walk both ways
  (`SzOwn` 96,089 against 93,493 without it: a program iterating a part needs only
  `IndexIterator`).
- **What a stale part answers (k74, 2026-11-21)**, each a JDK 25's answer to a probe
  (`.todo/artefacts/k74-java-library-a-copy-on-write-list-s-sublist-and-a-stale-part-s-answers/probes/`:
  `Cow.java` 160 lines and `Cow2.java` 60 over a `CopyOnWriteArrayList`'s part, `Range.java`
  over `removeRange`; `Sub.java` and `Sub2.java` of k62 asked again):
  - **`hasNext` of a walk of a part** is `cursor != list.walkEnd()`, `walkEnd()` being
    `size()` in `AbstractList` and the part's own length in `SubList`: a change behind the
    part does not move it, so a walk past a removal at the tail stops (`false`) and one at a
    stale part says `true`, and `next()` then refuses. No `instanceof`: `SzWalk` (a program's
    own `AbstractList` and a walk) is the same size as before.
  - **`subList` and `addAll(int, ..)` of a part** check the range against the part's own
    length BEFORE the change is looked at (`toIndex = 9`, `fromIndex(2) > toIndex(1)`,
    `Index: 9, Size: 2`); `subList` of a stale part then MAKES a part, which refuses when
    it is used (a JDK's `ArrayList` part: the child's use is the refusal; its construction
    is not); `addAll(i, nothing)` of a part answers `false` unchecked. `AbstractList.partRange`
    is the one range check.
  - **A part's spliterator** knows its size from when it was made
    (`Splits.counted(c, marks, size)`: `estimateSize()` does not take the iterator), the
    iterator at its first element, where a stale list refuses.
  - **Measured**: `SzSub` 142,241 -> 142,587 (+346), `SzOwn` 93,420 -> 93,837 (+417), a
    program of a `Vector` +21, of an `ArrayList` or `LinkedList` or an `AbstractList` with a
    walk nothing.
- **`removeRange` and `clear`**: `AbstractList.removeRange(from, to)` (protected) is
  `remove(from)` repeated, after `Index: from, Size: n` for a `from` outside the list;
  `AbstractList.clear()` is `removeRange(0, size())`, so it removes from the FRONT, a JDK's
  order (a program's list logging its `remove(int)` shows it); a part's `removeRange` is
  its parent's over the offset range, so `part.clear()` calls the ROOT's `removeRange` once
  with the range of the whole (`range(2,4)` for a part of a part), and a class of the program
  overriding it sees every clear. `ArrayList.removeRange` closes the gap in one move and is
  one change of `modCount` (also for an empty range), `From Index: 3 > To Index: 1` for a
  reversed one; it is not called by `ArrayList.clear()`, as a JDK's is not. A part's
  `clear()` of an `ArrayList` is now one `arraycopy` where it was `remove(i)` per element.
  Not the same: a range past the end of a program's list ends in that list's own refusal
  where a JDK's is a `NoSuchElementException` with the list's as its cause. Measured:
  `SzSubClear` (`list.subList(1, 4).clear()`) 140,177 -> 143,104, `SzOwnClear` (an
  `AbstractList` of the program, a walk and `clear()`) 133,859 -> 135,355. A first draft
  removed through `listIterator(from)` as the specification describes it and cost 9.7 KB and
  7.7 KB: `listIterator` is a virtual call, and every walk of the program and the library
  came with it.
- **`CopyOnWriteArrayList`'s part** (`COWSubList`, the name and `RandomAccess` a JDK's):
  the list, an offset, a size and the ARRAY the list had when the part was made or last
  changed the list; every member but the ones through the iterator first compares the list's
  array with it (`!=`), so a part of a part, a sibling and the parent refuse once one of them
  changes the list (a JDK's parts are all flat over the list), and a change that leaves the
  array as it was leaves the parts fresh: `addAll` and `removeIf` of nothing, `set` of the
  element that is there (a reference comparison), `remove(Object)` of a missing one,
  `retainAll` of everything, `addIfAbsent` of a present one, `clear()` of an empty list; and
  one that always puts another array in its place does not (`sort`, `replaceAll`, a part's
  `clear()` even of nothing). Its walks (`iterator`, `listIterator`, `spliterator`) are over
  a copy of the part's range of the array there was, so `for (x : part) part.add(0, x)` runs
  twice, as under a JDK, and change nothing. The order of the refusals is a JDK's: an index
  of `get`, `set`, `remove` and `addAll(i, ..)` first (`Index: 9, Size: 2`), the change
  first for `add(i, ..)`, `subList`, `listIterator` and `addAll(i, nothing)`. The list's own
  `addAll(int, ..)` is one new array (it was a loop of `add`), `listIterator(9)` says
  `Index: 9, Size: 6`. Measured: `SzCow` (a list, an add, a walk) 130,351 -> 130,372,
  `SzCowSub` 140,930 -> 141,799 (the old part was `AbstractList`'s). A first draft gave the
  list's walk a `lo` and a `hi` for the part: +499 B in every program holding the list.
- **Left** (`deviations.md`): the part of a `LinkedList` or a program's list, which a JDK
  builds from the ROOT's iterator, so its `subList` of a stale part makes a part that works,
  its `next()` past the end is a `NoSuchElementException`, its `remove()` before `next()` a
  `ConcurrentModificationException` (the `LinkedList`'s own iterator checks first). Those
  and the class name are one cause: a JDK has two part classes (`ArrayList$SubList` and
  `AbstractList$SubList`, with a `RandomAccessSubList` for a list that is `RandomAccess`) and
  here there is one, `java.util.AbstractList$SubList`, `RandomAccess` kept by a flag. Not
  made: a second class costs every program holding a part, and shows a program only
  `getClass()` and answers of a part that is stale, which a JDK's program does not rely on.
  A `CopyOnWriteArrayList` part's `trySplit` answers `null`, as no spliterator of the subset
  splits.
- Pins: java-spec `a-collection-is-java-source-and-a-program-s-class-can-be-one` (a walk
  over a part of three lists, the count of a program's class, `ArrayList`'s moves, a
  `CopyOnWriteArrayList`'s parts, a stale `ArrayList` part's answers, `removeRange` through
  `clear()`), `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`
  (`ArrayList`'s struct holds no `changes`).

## java.math (2026-11-20, k31)

`BigInteger`, `BigDecimal`, `MathContext` and `RoundingMode` are Java source
(`java.math` joined `LIBRARY_PACKAGES`), written from the API documentation; what it
leaves open is a JDK 25's answers to a grid.

- **The grid** (`.todo/artefacts/k31-java-math-biginteger-and-bigdecimal/Grid.java`):
  94,160 lines -- 20 integers and 39 decimals, every pair through every operation, each
  rounding mode at five scales and four precisions, ten math contexts, 98 texts a number
  is and is not read from, 31 pairs of numbers far apart, 150 seeded integers and 250 seeded decimals.
  The interpreter (17 s), the JVM (2.5 s), wasm (14.6 s) and the component (15.3 s)
  print a JDK's lines, all of them (the JDK itself 1.8 s).
- **A `BigInteger` is the host's integer held in a field** (`Object value`), its
  arithmetic 24 `native` members that are host forms (`+`, `truncate`, `ash`,
  `integer-length`, `logcount`, `isqrt`, `%java-digits`, `%java-nearest-binary`):
  `JavaIntrinsics.NATIVES`. The structure tells it from an `Integer` and a `Long`, and it
  stands under `Number` as the atomics do. **Measured against a tagged value** (the
  item's question; 20,000,000 sums of a 20-digit step, best of three): on wasm a cons
  `(n . :big)` written in Lisp 2.08 s, a structure written in Lisp 3.37 s, the Java
  class 2.92 s; on the JVM 1.15 / 1.30 / 1.13 s, a JDK's own class 1.18 s. So the
  structure costs a sum of small numbers 1.4 times on wasm and nothing on the JVM, and
  less as the numbers grow. Source was kept: an intrinsic's `toString`, `equals`,
  `hashCode` and class name are arms of run-time functions every program holds, a class
  of a program could not extend it, and `BigDecimal` is source either way.
- **A text is read a group at a time** (the most digits of the radix that always fit an
  int: 9 in ten, 7 in sixteen, 30 in two), and a digit that is none is refused with its
  group, as a JDK names it (`For input string: "4567890x1"`, ` under radix 16` after it
  where the radix is not ten); the zeros a number starts with are in no group. The order
  of the refusals: the radix, an empty text, a sign past the first character, a text of
  a sign alone.
- **`hashCode`** is no part of the API: read off the probe, a `BigInteger`'s is its
  magnitude's 32-bit words from the highest, each added to 31 times the ones before,
  times the sign; a `BigDecimal`'s 31 times its unscaled value's plus its scale.
- **A `BigDecimal` is a `BigInteger`, a scale and the count of its digits once asked.**
  Each operation is the specification's: the exact result at its preferred scale, then
  ONE rounding of an integer quotient (`divided`, `away`). What the grid decided:
  - `divide(x)`: the two as a fraction in lowest terms; the denominator may hold only
    twos and fives, and the larger count of the two is the digits added.
  - `divide(x, mc)`: the dividend raised so that the integer quotient has exactly the
    precision's digits, rounded once. An exact quotient loses the zeros it ends with as
    far as the preferred scale; a rounded one keeps its digits.
  - `add(x, mc)`: two zeros are added exactly; beside one zero the other number is
    rounded, then written with zeros toward the sum's scale as far as the precision
    takes them (`padded`); **a number wholly below both the other's last digit and the
    rounding place is replaced by one unit just below them** -- it moves the sum off the
    other number and across no boundary of any mode, so `1E+1000000000 + 1` at seven
    digits is seven digits of work.
  - `sqrt(mc)`: the integer root (`isqrt`) of the value raised to hold one digit more
    than asked, rounded once with what the root left over; right in the eight modes on
    every line. The result loses the zeros it ends with as far as `scale / 2` even
    where it was rounded (`0.5` at two digits, `DOWN`, is `0.7`).
  - `pow(n, mc)` is the algorithm the documentation gives (squaring from the exponent's
    highest bit at the precision plus the exponent's digits plus one).
  - `divideToIntegralValue(x, mc)`: the whole quotient with its zeros off; more digits
    than the precision is `Division impossible`, fewer are padded toward the preferred
    scale.
  - `divide(x, scale, mode)` by zero has a JDK's two wordings: `/ by zero` where the
    scale moves a number at most 18 places and the dividend moved is below 2^63, else
    `BigInteger divide by zero` (224 probe lines, no exception).
  - A text: the exponent is read before the digits are counted; at most ten exponent
    digits past its zeros; a scale outside an int is `Exponent overflow.`. A text that
    stops where a character must follow has NO message where it ends its array (every
    string: `""`, `"1e"`) and `No digits found.` / `No exponent digits.` where it is a
    range ending inside a `char[]`.
  - `compareTo`, the exact conversions, `longValue` and `doubleValue` decide by the place
    of the first digit before any power of ten is made, so `1e1000000000` compares,
    overflows and is `Infinity` at once. `doubleValue` / `floatValue` are
    `%java-nearest-binary` of the ratio: one rounding.
- **`MessageFormat` asks `rontolisp.internal.Exact`** (`written(3)`: the plain digits
  rounded half even at three places, the sign kept where no digit is left: `-0`), which
  the two classes implement. An interface, not two `instanceof`: a test of an interface
  and a call through it hold only the classes a program MADE, so a program with no big
  number pays 33 B (one message 175,758 -> 175,791 B of wasm; YAVI's classic example
  655,678 -> 655,716).
- **An enum may declare an overload of `valueOf` or `values`** (`RoundingMode.valueOf(int)`;
  until then any method of the name hid the enum's own and `valueOf("X")` was `no
  suitable method found`): `JavaClasses.enumMethod` looks for the signature.
- Sizes (wasm): a program summing `BigInteger`s 106,595 B; one adding two `BigDecimal`s
  and rounding the sum 177,312; a program naming neither is byte-identical.
- **YAVI**: `patch.sh` lost its fourth edit; `UntypedInput.convertNumber`, `decimal` and
  `typeName` are lowered as written, but the `Year` arm, which went to the first edit
  (`java.time`, gone with k35). The schema example with two edits left prints a JDK's
  lines on the four backends: wasm 1,095,209 B (1,032,990 with the conversion cut out),
  the component 1,099,574, the jar 407,992; with five inputs that run the conversion
  (a `long`, two doubles, a `BigDecimal`, a `BigInteger` given for an `Integer`)
  1,095,450 B. `shelf/Price.java` beside the grid (`_bigDecimal`, `_bigInteger`, their
  bounds in the messages) prints a JDK's 19 lines on the four: wasm 1,034,348 B.
- **`String.format` writes the two by their own digits** (k26, 2026-11-20): `%d`, `%x`,
  `%X` and `%o` of a `BigInteger`, `%f`, `%e` and `%E` of a `BigDecimal`, and the
  `IllegalFormatConversionException` naming the class for the conversions each does not
  take. The lowering hands `%java-format` a third function beside `%string` and the hash:
  `(lambda (conversion flags width precision argument) (%java-format-exact .. (Exact.read
  argument)))`, and only in a program that MADE a class under `rontolisp.internal.Exact`
  (`JavaLowering.exactFunction`, asked by name so that no file is read for the question;
  `nil` otherwise). `Exact.digits()` answers `#(integer scale)`, the scale `null` for a
  `BigInteger`. Learned from a JDK 25 over 1,006 lines (`Fmt.java` of the k26 artefacts):
  `%x` / `%o` write the sign ahead of the digits (`-ff`, `-0xff`, `(ff)`), the zeros of
  a width stand between the prefix and the digits; `%f` / `%e` round half up; a zero's
  exponent is the place its scale gives it (`0.00` is `0.000000e-02`). **Measured**: a
  program that formats and makes no such number is 139 B smaller than before (154,286 ->
  154,147 B of wasm: the rounding of digits became one function for both); one that
  formats and makes a `BigInteger` pays 8,111 B (184,122 -> 192,233) whatever it formats,
  since the arguments are an `Object[]` -- `%java-format-exact` 3.7 KB, the radix and
  the scientific forms 2.5 KB, `_dispatch_5` 0.5 KB.
  Found beside it: the zeros of a width were written ahead of a negative number's
  parenthesis (`%(010d` of -5 was `0000000(5)`), now inside it as a JDK writes them.
- Not there (`.todo/k90`): the primes (`isProbablePrime`, `nextProbablePrime`,
  `probablePrime`, the constructors of random bits), refused by name.
- Pins: the `standalone:` case `big-integers-and-decimals-compute-round-and-print-exactly`
  (a file of its own: a class under `Number` changes how the corpus program lowers
  `Number`; its `formatted`: 27 controls over five integers, 25 over sixteen decimals);
  the parentheses line of `format-renders-the-conversions-programs-write`;
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the third
  function of a format, `NIL` in a program with no such number); `valueOf(int)` of an
  enum in `an-enum-is-a-class-whose-constants-are-made-once`.

## java.time (2026-11-20, k35)

The classes a value is read from its text as -- `LocalDate`, `LocalTime`, `LocalDateTime`,
`OffsetDateTime`, `ZonedDateTime`, `Instant`, `Year`, `YearMonth`, `Period` beside k05's
`Duration` -- with `ZoneOffset`, `ZoneId`, `ZoneRules`, `Clock`, `DayOfWeek`, `Month`,
`java.time.temporal` (the six interfaces, `ChronoField`, `ChronoUnit`, `ValueRange`) and
the three interfaces of `java.time.chrono` are Java source, written from the API
documentation; what it leaves open is a JDK 25's answers to a grid. `java.time.chrono`,
`.format`, `.temporal` and `.zone` joined `LIBRARY_PACKAGES`.

- **The grid** (`.todo/artefacts/k35-java-time-the-classes-a-value-is-read-from-its-text-as/Grid.java`):
  27,197 lines -- 503 texts through the ten `parse` methods, 25 dates, 10 times, 28
  date-times, 32 offset date-times, 30 zoned ones, 15 instants, 13 years, 12 months and
  17 periods through their members, every pair of each compared and measured in every
  unit, all 30 fields read and set on each class, 104 zone ids, the clocks, 800 seeded
  days and seconds. The interpreter (12 s), the JVM (2.3 s), wasm (1.4 s, 959,021 B)
  and the component (1.5 s) print a JDK's lines, all of them (the JDK itself 2.2 s).
- **The calendar is arithmetic over the day counted from 0000-01-01** (`Iso`): a year
  starts `365 y + floor((y + 3) / 4) - floor((y + 99) / 100) + floor((y + 399) / 400)`
  days in; the year of a day is that count's inverse estimated by 400 years in 146,097
  days and stepped at most once; a month by a table of twelve starts. Our own
  formulation, no port: 800 seeded days agree with a JDK over the whole range. An
  `Instant` is written through it too, since its years go a year past `LocalDate`'s.
- **A text is read first and its fields checked after** (`IsoText`, one scanner for
  the eight ISO forms), which is what gives a JDK's three kinds of refusal: `could not
  be parsed at index n` where the text stops being the form, `unparsed text found at
  index n` where it goes on past it, and `could not be parsed: <why>` with the
  `DateTimeException` as its cause where a field has no such value. What the grid
  decided:
  - a year is four digits; more (to ten) need a sign, and a `+` needs more; `-0000` is
    none. `Year.parse` alone takes any sign and one to nine digits.
  - the seconds of a time and their fraction are optional together (`10:15:3` is
    `10:15` and unparsed text at 5); a point may stand with no digit after it.
  - the fields are refused in the order year, month, day, date, minute, hour, second,
    then the offset's range.
  - an offset is `Z` in either case or `±hh[:mm[:ss]]`; minutes or seconds of 60 are
    not read (the text ends before them). The hours alone are an offset for an
    `OffsetDateTime` and a `ZonedDateTime`, not for an `Instant` nor between brackets.
    Hours of 24 to 59 are read and refused AT ONCE (`Value out of range: Hour[0-23],
    Minute[0-59], Second[0-59]`), before a wrong month and before unparsed text; 60 and
    above are no offset.
  - `Instant.parse` wants its seconds, takes an offset with its minutes, reads
    `24:00:00` as the next day's start and a second 60 after `23:59` as 59, and
    refuses a text whose fields are wrong at index 0 with no cause.
  - a zone in brackets is `Z`, an offset with its minutes, `UTC` / `GMT` / `UT` with
    one, or a name; one that is none of them leaves the bracket unread, so the text is
    refused there as unparsed. Given both, the offset places the instant and the zone
    says how it is written (`10:15+01:00[+02:00]` is `11:15+02:00`).
  - a text over 64 characters is cut to 64 and `...` in the message.
  - Where a JDK lists the fields it could make no value of (`Unable to obtain Instant
    from TemporalAccessor: {..}`), their order changes from run to run (a hash map of
    enum constants); the library writes one order, and the corpus compares the words
    before the brace.
- **A zone every program knows keeps one offset** (`ZoneId.fixed()`): an offset, `UTC` /
  `GMT` / `UT` alone or before an offset (`ZoneRegion`, the id normalized as a JDK does:
  `UTC+1` is `UTC+01:00`, `GMT-00:00` is `GMT`), and the 42 names of the tz database
  that are a fixed offset by definition (`Etc/GMT+5`, `Etc/UTC`, `Zulu`; computed, no
  table). **A region whose offset changes is known to a program that holds it**
  ("java.time: the regions" below); to any other it is unknown (`ZoneRulesException:
  Unknown time-zone ID`, the bracket of a `ZonedDateTime` left unread), as a name its
  database lacks is under a JDK, and its `ZonedDateTime` has no gap and no overlap: the
  members that settle one answer `this`. Measured before k35 chose (`Zones.java` of the
  artefacts, asked of JDK 25's `java.time.zone`): 604 names, 322 sets of rules, 18,001
  changes of offset and 222 yearly rules, some 123 KB in a plain encoding -- more than a
  tenth of the YAVI schema example, paid by every program that reads a zoned text if
  `ZonedDateTime.parse` had to know every name. The host's rules were not taken either:
  wasm has no host to ask, and a zoned text would be read differently on the four
  backends.
- **`ZoneId.systemDefault()` is the host's offset NOW** as a `ZoneOffset` (the region
  `TZ` names where the program holds it: "java.time: the regions"): the native
  `ZoneId.local0` is `%local-time-offset`, the one question every backend can answer
  (`.kb/time-environment-builtins.md`; on wasm `TZ` and the zone files, else UTC, and
  2.3 KB for the reader). `Instant.now()` is `%unix-nano-time`. Nothing else is
  native.
- **The fields and the units are the API's interfaces**, so a program's own
  `TemporalField`, unit, adjuster or amount goes through; each class answers the
  `ChronoField` / `ChronoUnit` it has in a `switch` and hands any other to the field
  or unit itself. `from(TemporalAccessor)` is an `instanceof` over the library's
  classes, which a test holds only the made classes of, and since k43 `query` and the
  fields where the program can hold a value of another class ("java.time: the formatter").
  `Period.addTo` adds years as years where there are no months (a `Year` takes a
  period of years), the rest as months, then the days.
- **Read off the grid, in no documentation**: the hash codes (`LocalDate`
  `(year & 0xFFFFF800) ^ ((year << 11) + (month << 6) + day)`, `LocalTime` its
  nanosecond of day as a long's, `Instant` as `Duration`, `YearMonth`
  `year ^ (month << 27)`, `Period` `years + rotl(months, 8) + rotl(days, 16)`, a
  date-time the xor of its parts, a zoned one with its zone's rotated 3; a region its
  id's); `compareTo` of two dates is the difference of the first field that differs;
  `LocalDate.plusDays` past the last day names the year where the day lands within 59
  days of December's start and the epoch day otherwise; `INSTANT_SECONDS`' range is
  an `Instant`'s, not a long's; `Instant.until` in milliseconds and microseconds is
  the exact difference cut toward zero; `ZoneOffset.of` reads by the text's length
  and refuses non-digits before a missing sign (`UT` is `non numeric characters
  found: U0T`). `ValueRange`, `ZoneRules` and the clocks hash to numbers of ours.
- **What a program pays** (wasm): one parsing a date 141,218 B, a time 136,931, a
  zoned date-time 178,759, `LocalDate.of(..).plusDays(1)` 106,233 (a program that
  catches an exception 55,019, one parsing a `Duration` 155,029); `LocalDate.now()`
  118,591. A program naming none is byte-identical (YAVI's classic example 655,716 B
  before and after). **A class object costs with its interfaces**: of the 119,841 B the
  schema example grew by, 69 KB are the functions of `java.time`, 14 KB the top level
  (15 class objects: the eight classes and the seven interfaces above them) and the
  rest dispatch arms.
- **YAVI**: `patch.sh` lost its first edit; `UntypedInput`'s table and the `Year` arm of
  `convertNumber` are lowered as written. The schema example with ONE edit left
  (`java.util.Date` and `java.sql`, gone with k37) prints a JDK's 23 lines on the four
  backends: wasm 1,215,291 B (1,095,450 with the table cut), the component 1,219,661,
  the jar 438,757, the interpreter 6.1 s. `shelf/Loan.java` beside the grid -- a schema
  of the eight classes with the temporal constraints (`after`, `between`, `past` of a
  fixed clock, a field's predicate), texts, values already of the type and numbers for
  the year -- prints a JDK's 77 lines on the four: wasm 1,256,341 B.
- The classes k35 left came with k52 ("java.time: what the formatter left" below).
  `Duration.dividedBy` stays `.todo/k82`'s. The formatter and the queries came with k43
  ("java.time: the formatter" below).
- Pins: java-spec `dates-times-and-instants-are-read-from-their-texts-and-computed-with`
  (146 lines of the grid's kinds); a class the subset lacks is `java.util.Currency` and
  `java.util.EventListener` in `JavaLoweringTest#aLibraryOfTheSourcePathIsLoweredAsFarAsTheProgramReaches`
  and in the library of `a-program-uses-a-library-read-as-source-from-its-source-path`
  (it was `java.time.LocalDate`).

## java.time: the regions (2026-11-20, k42)

**Invariant: a program holds the regions of the tz database it names, each with the
changes of its offset as a JDK's `java.time.zone` answers them; a program that holds none
has nothing of what a region needs -- the forms it had before.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md).

- **Who pays.** `ZoneId.of` and `ZoneId.getAvailableZoneIds` are `native`, so every call
  of the program's -- a method reference too -- comes through an emitter that notes the
  name first (`JavaLowering.namesZone`): a constant naming a region of the table adds it
  to `JavaClasses.regions`, a name computed where the program runs or the question for
  the names sets `everyRegion`, any other constant (`"UTC"`, `"+09:00"`, a name no
  database has) notes nothing. A call written in the front end's own library notes
  nothing either. The first of each lowers the program again (`changed()`); a session
  carries both (`Session.regions`: a zone lives on in a variable). The other natives that
  name a zone note the same way (k51): `ZoneRulesProvider.getRules` / `getVersions` as
  `ZoneId.of`, its `getAvailableZoneIds` as `ZoneId`'s; `ZoneId.of(id, map)` the region
  `ZoneId.SHORT_IDS` gives a constant where the map is that field (`JavaIntrinsics.reads`:
  its variable, after its class's initialization), every region for any other map.
- **A zoned text the program's own files parse brings what it names** (k51,
  `JavaLowering.readsZones`): `ZonedDateTime.parse` of a constant text the regions whose
  names occur in it (`JavaZones.within`; `"...+02:00"` none), of a text computed where
  the program runs every region. **A library of the source path brings none**
  (`Origin.DEPENDENCY`, as the front end's own): YAVI 0.18.0 reaches
  `ZonedDateTime::parse` in every schema through its table of types, where `Booking`
  parses nothing, and would grow by the whole table (`.todo/k50` measures the release
  without that reach). **The host's zone brings nothing**: `LocalDate.now()` reaches
  `systemDefault`. **No option of the compile holds regions** (decided k51): the source
  says it already -- a constant `ZoneId.of` per region, one `ZoneId.getAvailableZoneIds()`
  for all -- the same way on the CLI, the Maven plugin, the playground and a session,
  where an option would be one more input to four embedders and the same text would
  compile to two behaviours.
- **`ZoneId.regional()` is a question answered while lowering**
  (`JavaIntrinsics.answered`, `JavaLowering.answered`): where it is the whole condition of
  an `if` statement or of a conditional expression in the library's own source, only the
  statement or the operand the answer takes is lowered, and nothing after a taken
  statement that never completes. Everything a region needs in a class a program without
  one reaches stands behind it (`ZonedDateTime`, the four `ofInstant`, `atStartOfDay`,
  `ZoneId.region`, `systemDefault`), written so that what is left is the old statement
  untouched. Measured against the build before (wasm): `examples/java/yavi/Member.java`
  663,174 B, the same bytes; `Booking.java` 1,204,017 B, the same size and, compared as
  `--dump-ir` lines, the same forms in another order (the tokens of the 10 lines that
  differ are equal as multisets: the arms of a dispatch, the order classes are
  initialized in); `LocalDate.now().getYear()` 118,491 -> 118,482 (the same forms, a
  structure defined two lines later). The order moves because `ZoneRules` now names
  `ZoneOffsetTransition` and `ZoneOffsetTransitionRule` in its members, and a class enters
  the table where a member of a class already there names it: no new API member can keep
  the bytes.
- **The table** (`src/main/resources/am/ik/rontolisp/java/zone-rules.txt`, read by
  `JavaZones`) is generated by `JavaZoneRulesTest` from the running JDK's public
  `java.time.zone` and pinned to it, the `UnicodeNormalizationTablesTest` way: a zone's
  `getTransitions()`, its `getTransitionRules()`, and its standard offset, which no list
  holds, asked every five days from 1800 to 2100 and a change found to the second (1,110
  changes over the 322 sets, 189 of them of the standard offset alone, at no transition).
  JDK 25.0.5 answers tz database 2026e: 562 names beside the 42 computed ones (the seven
  fixed names of `SystemV/` are in the table), 329 sets of rules. A line of the file is the
  names sharing a set, then the set's text; `tzdb` names the version
  (`ZoneRulesProvider.getVersions`), `short` the pairs of `ZoneId.SHORT_IDS` (read by
  `JavaZones` for the noting, and by the library as `ZoneId.short0()` where a program
  reads the field: JDK 25 maps `EST` to `America/Panama`, `MST` and `HST` to regions too,
  where the documentation of JDK 8 had offsets). **A JDK with a newer tz database fails the test**,
  which writes `target/zone-rules.txt` to copy over the shipped file; an older one is
  skipped, saying so (a JDK update moves the tz database inside one feature release,
  where it does not move Unicode).
- **The encoding** (`JavaZoneRulesTest.text`, read by `java.time.zone.ZoneChanges` of the
  library): numbers in digits of five bits and a sixth saying more follow, in the
  characters `$` to `d` without the backslash. A set is its offsets (at most eight; in
  quarters of an hour where they are whole ones), the two it starts with, its yearly
  rules, then its changes as RUNS: years in a row that one rule read from the wall clock
  gives -- for each change not yet in a run, the day as it is, as the first such day of
  the week on or after one of the seven days before, or as the last on or before one of
  the seven after, whichever goes on for the most years (a change at midnight may be the
  end of the day before). 18,190 changes (the 18,001 transitions and the 189 of the
  standard offset alone) are 5,555 runs, 3,475 of them a single year. Measured, the
  rules of all sets: a change as the seconds since the one before and an offset 123 KB
  (k35); runs with three offsets each some 51 KB; the offsets as one number and a single
  year as a bit, what ships, 40,825 B. Not taken: sharing the tail of a set with another
  (a sixth of the changes saved); a dictionary of rules across sets (about half of the
  runs' rules are distinct: the references cost what it saves); a general compressor over
  the sets (gzip makes some 15 KB of them). **Copies of an earlier set's runs** (k51,
  measured over the shipped table, a run equal in its years, rule and three offsets):
  415 copies of five characters save 11,492 B of the 35,456 the runs are, only in a
  program holding all; a reader following them (find the set, skip its head, read its
  runs into this set's offsets) is 8,629 B of wasm, paid by every program holding a
  region, and `JavaZones.table` would have to resolve a copy whose set is not held. Not
  taken. **Measured again after k56** (2026-11-20, a reader re-written from that
  description into `region`, `copied` and `place`, a program holding Paris): +8,394 B on the
  build before, +5,867 after (`copied` 6,661 -> 4,651 B, most of what is left its `long[]`
  reads, the generic dispatch); one with an `int[]` and an exception of its own also cost
  `placed` 2,181 B of proofs the type-test fold had. Every program holding a region pays
  it, 11,492 B come back only where one holds them all: still not taken.
- **A program's table** (`JavaZones.table`, the constant `ZoneChanges.table0()` is) holds
  the regions it holds: the areas its names are shortened by (`America/`: a name up to
  its last slash, written as one character from `#`; an area is named where that saves
  more than it costs), then for each set the names that have it and its text. All of them
  46,661 B; `Asia/Tokyo` alone 57, `Europe/Paris` 328, Paris and New York 475.
- **Not taken: a region as arrays the compiler writes** (k51), to spare a program naming
  its regions the reader: Paris's 102 listed changes as three `long[]` literals are
  38,732 B of wasm (each second past an `i31` a boxed literal) against its 328 B of text,
  so the reader (17.9 KB) is the cheaper half from the first region on. Some 200 B of
  wasm per `int` or `long` operation were most of what the reader cost; since k56 it is
  13.2 KB ("Numbers"), most of what is left its array accesses.
- **The rules in the library**: `ZoneChanges` (under `ZoneRules`, which stays the rules
  of one offset: a program making none has `ZoneRules` as it was) holds the seconds of
  the changes with the clock's and the standard offset after each, and the yearly rules
  as numbers (`Yearly`). `ZoneOffsetTransition` and `ZoneOffsetTransitionRule` are made
  where a program asks for one. What a JDK answered and no documentation states: a
  yearly rule counts only past the last listed change of the clock and not at all where
  none is listed (`ZoneRules.of` with rules alone answers its base offset for good); a
  change is before any instant after its second, to the nanosecond; a local date-time
  from the lesser to the greater of a change's two local times is in its gap or its
  overlap, the greater excluded; `isFixedOffset` wants no list and equal base offsets,
  and such rules equal `ZoneRules.of(offset)`.
- **The doors between the two packages are natives**: `ZoneId.held0` / `names0` and
  `TzRegion.wall0` / `placed0` are static methods of `ZoneChanges`
  (`JavaIntrinsics.library`), so `java.time` asks the rules in numbers -- the offset at a
  second, and what a local second is: one offset, or in the gap or the overlap of a
  change -- and `java.time.zone` has no public member a JDK lacks.
- **A `ZonedDateTime` in a region** (a JDK's answers, pinned): a local date-time in a gap
  is later by the gap's length, one in an overlap takes the offset it is given where that
  is the later and the earlier otherwise; a date-based field or unit, `truncatedTo` and a
  `Period` (added whole, then settled once) move the local date-time and keep the offset
  where it holds; a time-based unit and a `Duration` move the instant; `until` in a
  time-based unit is the instants'; `with(ZoneOffset)` and `OFFSET_SECONDS` change the
  offset only to the other one of an overlap; a text's offset places the instant and its
  region says how it is written; `LocalDate.atStartOfDay(zone)` of a day whose midnight
  the zone skips is where the gap ends, not midnight moved by its length (Toronto
  1919-03-31 is `00:30`: the one line the first walk differed in).
- **`ZoneId.systemDefault()`** in a program holding a region is the zone the host names
  where the program knows it (a region it holds, or a fixed name such as `Etc/UTC`), the
  host's offset now otherwise. The name, as JDK 25.0.5 answered on Linux: `TZ` with a
  leading colon dropped; where `TZ` is unset or empty, the path the link `/etc/localtime`
  holds after `zoneinfo/` (`ZoneId.hostZone`, through `%read-link`, so a `.wasm` reads it
  where `/etc` is granted). A JDK answers `GMT+09:00` for `TZ=JST-9` and `GMT` for a name
  it lacks; here the offset now. Pinned by `LocalTimeZoneTest` (`TZ=:<zone>` on the four
  backends, an `/etc` of the test's on the two wasm legs).
- **Five years of yearly rules are kept** (k51): `ZoneChanges.ruled` made a `LocalDate`
  and six `Yearly.at` for each question past the last change; it now keeps the changes of
  five years about the one the second's seconds divided by an average Gregorian year
  (31,556,952) give -- that number is the second's year or one beside it (the calendar is
  never more than 1.2 days off the average, measured over a 400-year cycle), so the
  window holds the year before and after -- until a question leaves it. Measured (Paris,
  best of five, wasm / JVM / JDK in ns): `ZonedDateTime.of(local, zone)` in 2026
  5,286 -> 2,214 / 1,367 -> 724 / 282; `ZoneRules.getOffset(Instant)` 3,572 -> 1,089 /
  1,654 -> 437 / 81; `ZonedDateTime.now(zone)` about 6,000 -> 1,800 / about 1,200 -> 600 /
  233; `now(zone).toString()` 8,361 -> 6,687 / 5,659 -> 4,858 / 614. The walk: JVM 15 ->
  14 s, wasm 29 -> 26 s, the interpreter 5.9 -> 5.7 min: the rest is its lines' strings.
  The bytes: a program holding a region +6.7 KB of wasm (`ruled` 3,546 -> 7,528,
  `placed` 5,426 -> 7,699 from the same forms; since k56 4,829 and 5,166); a
  window of one year with its bounds made by `LocalDate` was +8.9 KB, an index computed
  per element instead of two loops +9.7.
- **What a program pays** (wasm, `ZonedDateTime.of(.., ZoneId.of(..)).plusHours(1)`;
  k42's numbers, before the kept years' 6.7 KB):
  `"UTC"` 139,793 B before and after; `"Asia/Tokyo"` 190,054 (+50,261: `ZoneChanges`
  30.8 KB -- reading the table 18 KB of it, `placed` 5.8, the yearly rules 3.8 --,
  `Yearly` 4.0, the static initialization 4.2, `LocalDateTime` 3.6); `"Europe/Paris"`
  190,327; a computed name 237,821. `LocalDate.now(ZoneId.of("Asia/Tokyo"))` 82,239 ->
  124,099. **How it came down from +172 KB** (311,703 for Tokyo), each step measured with
  `-Drontolisp.wasm.debug-func-sizes`: lists of `ZoneOffsetTransition`, a `HashMap` of the
  regions read and a `Duration` for a gap's length made every class of them with its
  `toString` / `equals` / `hashCode` (236,399 with arrays of numbers); a cast to
  `ZoneChanges` brought `ClassCastException` and `%class-name`, `System.arraycopy` 6 KB of
  `replace`, and two passes one list per kind of change (217,871 with one list of
  changes); and `"..." + offset` in `ZoneRules.toString` is the generic `%string` of a
  value the backend cannot type, which kept the float arm of `%java-to-string` and 17 KB
  of float printing (190,054 with `offset.getId()`). Every `int` and `long` operation of
  the library is masked to its width, which was what the reader's 18 KB were ("Numbers":
  13.2 KB since k56).
- **The walk** (`.todo/artefacts/k42-java-time-the-rules-of-a-region/Regions.java`): every
  zone's changes and yearly rules, the instants and local date-times on, beside and
  between every change (and those the yearly rules make in five later years), seeded
  ones anywhere, a zoned date-time moved across the changes: 604 zones, 362,528 lines,
  each zone a line of their hash. The interpreter (5.9 min), the JVM (15 s), wasm (29 s,
  594,888 B) and the component (28 s) print a JDK's 605 lines (the JDK 3.2 s).
- **A newer tz database**: under a JDK that has it, `./mvnw test -Dtest=JavaZoneRulesTest`
  fails and writes `target/zone-rules.txt`; copy it over the shipped file, run the test
  again, then `-Dtest=JavaSpecE2eTest` (the region case's lines are the JDK's own) and
  the walk on the four backends.
- **`ZoneRulesProvider`** (k51) is the provider of the table the program was compiled
  with: `getRules` of a name of the tz database (not `UT`, no offset alone or after a
  prefix: a JDK's `ZoneRulesException`), `getVersions` a new `TreeMap` of the table's
  version, `getAvailableZoneIds` the names unmodifiable, `refresh` false.
  `registerProvider` and a provider's own methods are not there.
- Pins: `JavaZoneRulesTest` (the shipped table is the JDK's; a program's table holds what
  it names -- by `ZoneId.of`, a short name, the provider, a zoned text -- and a library of
  the source path's parse nothing; a program naming none has no form of `ZoneChanges`; a
  session goes on holding); java-spec `a-program-holds-the-regions-of-the-tz-database-it-names`
  (a file of its own, 108 lines: the corpus's one program holds no region, which is what
  its `dates-times-and-instants` case runs as); `LocalTimeZoneTest` (the host's zone).

## java.time: the formatter (2026-11-20, k43)

**Invariant: a formatter writes a value through the parts its pattern or builder names,
reads a text back through them and makes the fields a date and a time in its resolver
style, as a JDK 25 answers; a program that makes no formatter has nothing of it, one that
reads no ISO form makes none, and the classes read a value of another class through its
fields only in a program that can hold one.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md).

- **The shape** (`java.time.format`, Java source): a formatter is a `Step.Group` of
  parts (a literal, a number, a reduced number, a fraction, words, an offset, a localized
  offset, a zone's name, an instant, a group, a setting, a pad, a default), a locale, a
  resolver style and a zone. Writing asks each part to print (`Writing`: inside an
  optional part a field the value lacks writes nothing); reading asks each part to read at
  a position (`Reading`), which answers where it ends or the complement of where it
  failed, then `Parsed.resolve`. `Patterns` makes a pattern's parts by the API
  documentation's table of letters; `Texts` holds the words of months, days, eras and
  half days of the five locales `Locale` has (CLDR's as a JDK writes them: the root's
  `BCE`, the British `Sept` and `am`, the Canadian `a.m.`). `Parsed` has the name a JDK's
  refusals show (`of type java.time.format.Parsed`).
- **The classes' own `parse` stays `IsoText`'s**, the formatter a second reader of the
  same fields: measured, `LocalDate.parse(text)` is 141,206 B of wasm and
  `LocalDate.parse(text, ISO_LOCAL_DATE)` 365,475, which one engine would put in every
  program that reads a date. The 503 texts of k35's grid read through the ISO forms give
  the JDK's answers, so the two readers agree.
- **What the grid decided** (`Grid.java` below), in no documentation:
  - a run of numbers with no text between: the first keeps the digits of the fixed-width
    numbers after it, which read their width exactly and take no sign; the first reads
    at least its least width and at most 19 digits, one fewer where 19 pass a long.
    Strictly a sign is the style's (`EXCEEDS_PAD`: a `+` only past the pad width, none
    without one past it; no `-0`); leniently any sign unless the width is fixed. A
    reduced number read in its width is the value nearest after the base; in another
    width, or signed, it is the number itself. Leniently "any count of digits" was
    wrong: k52 measured (2026-11-20, 1,760 lines) nine at most, and found a fraction to
    be one of a run ("java.time: what the formatter left").
  - words: the longest standing there, of two equal ones the later value (`J` is July);
    leniently those of every style, then a number; a field with no words is a number.
  - an offset: the no-offset text first, whatever follows (`x` reads `+0030` as `+00`);
    hours of two digits are no offset from 60 and refused at once from 24; an hour of
    one digit takes the digits the pattern can read, an odd count one digit, and is
    refused at once past 23 with its minutes and seconds; leniently the minutes and the
    seconds may each be there, with colons only in a pattern of the hours alone or one
    with colons; with an empty no-offset text a text that is no offset is a zero one,
    reading nothing.
  - a zone's name: an offset, `UTC` / `GMT` / `UT` with an offset or alone, or the
    longest name of a zone the program has (`ZoneId.region` through the native
    `Step.region0`, which notes no name: "java.time: the regions").
  - an instant is read as `ISO_INSTANT` writes it with any offset within 18 hours, the end
    of a day as the next day's start and a second 60 as 59 (`parsedLeapSecond`); one past
    that is refused at the part's start.
  - a pad reads its width whole strictly; a setting changed inside an optional part that
    fails stays changed (a JDK's answer); a field read twice must be read the same.
  - resolving: a year of an era is a year (strictly only with an era or a year; an era
    alone is checked in every style); then a date of the year, month and day (strictly
    `LocalDate.of`; smartly the day cut to the month's last; leniently added up), of the
    day of the year, or of an aligned week and a day; an epoch day is the date the other
    fields are checked against. The time fields are converted (clock hours, half days,
    the fields of a day, thousandths and millionths into nanoseconds, each conflict
    `while resolving` the field), then a time of the hour, minute, second and nanosecond,
    each where the one before is: checked minute, hour (smartly `24:00` is the next day),
    second, nanosecond. A time not made gets its nanoseconds and their thousandths and
    millionths where there is a second or an instant; with no hour each field is checked
    by itself. Every field left is checked against the date or the time (`Conflict found:
    Field .. derived from ..`). An instant read with a zone or an offset is the date and
    the time there, over what the fields made; a date and a time with one get their
    instant. Half a day alone is its middle (`AM` is `06:00`).
- **Who pays: two questions answered while lowering** (`JavaIntrinsics.answered`):
  - `DateTimeFormatter.wanted()`: each ISO form's initializer is `wanted() ? .. : null`,
    answered by whether the program reads that static field (`JavaLowering.noteWanted`,
    `JavaClasses.wanted`; the first read lowers the program again). Measured: a format
    by a pattern 322,993 B, 333,055 with every form made; a parse 428,993 against
    446,350.
  - `Iso.foreign()`: `from` of `LocalDate`, `LocalTime`, `LocalDateTime`,
    `OffsetDateTime`, `ZonedDateTime`, `ZoneOffset` and `ZoneId` read a value of another
    class through `query` and its fields only where the program can hold one
    (`JavaLowering.foreignTemporals`: a class it makes outside `java.time` itself, a
    formatter's `Parsed` or a class of its own; a session always). Measured: a program of
    `LocalDate.until` is 167,943 B, 202,041 with that reading in every program.
- **A program making no formatter is unchanged**: YAVI's `Member.java` the same bytes,
  `Booking.java` and every program of the k35 and k42 tables the same bytes or the same
  forms in another order (`LocalDate` now names `DateTimeFormatter` and `TemporalQuery`,
  which moves classes in the table). The table is in the artefacts.
- **What a program pays** (wasm): a date written by a pattern 322,993 B, one read by a
  pattern 428,993, by `ISO_LOCAL_DATE` 365,475; YAVI's `isoLocalDate()` constraint
  946,257 (`Member` 663,174). Of the format program's functions (262 KB): the run time 50
  KB more than a program adding a day (the float arm of a generic `%string` 16 KB, which
  `"" + field` of an interface type brings), the parts 27, the top level 25 (the class
  initializations of `ChronoUnit`, the texts, the patterns of an offset), `Patterns` and
  the builder 16; a parse adds `Parsed` (38 KB) and the parts' reading.
- **The grid** (`.todo/artefacts/k43-java-time-a-formatter-and-the-classes-k35-left/Grid.java`):
  8,394 lines; the interpreter (7.2 s), the JVM (2.2 s), wasm (0.8 s, 1,072,395 B) and
  the component (0.9 s) print a JDK's lines (the JDK 2.0 s). The k35 grid and the k42
  walk print theirs as before.
- **YAVI**: `CharSequenceConstraint.isoLocalDate()` and `localDate(pattern)`
  (`DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)
  .parse(x)`) run: the artefacts' `yavi/Event.java` prints a JDK's lines on the four
  backends. Strictly a pattern of `yyyy` makes no date (the year of an era stays a
  field), so such a check takes `2026-02-30` as a JDK does.
- **Not there**: what k52 left is `.todo/k57` ("java.time: what the formatter left").
- Pins: java-spec `a-formatter-writes-and-reads-a-date-by-a-pattern` (standalone, 70
  lines; the corpus program above makes no formatter and so keeps the classes' own
  ways); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the
  two questions).

## java.time: what the formatter left (2026-11-20, k52)

**Invariant: the classes k35 left and the letters k43 refused write, read and resolve as
a JDK 25 answers; a program pays for the calendar only where it can ask a value for it,
for resolving a field that is no `ChronoField` only where it makes one, and for the
letters of weeks, quarters and Julian days only where a pattern of it can name one.**
Provenance: [java-frontend-provenance.md](java-frontend-provenance.md). The grid and
the sizes: `.todo/artefacts/k52-java-time-what-the-formatter-left/`.

- **The classes** (Java source): `MonthDay` (`--MM-dd` read by `IsoText`; a text whose
  fields make no day is refused with the words a JDK's formatter shows, `Unable to
  obtain MonthDay from TemporalAccessor: {MonthOfYear=.., DayOfMonth=..},ISO of type
  java.time.format.Parsed`, its list in one order); `OffsetTime` (`ISO_OFFSET_TIME`'s
  text; an offset past 18 hours is the JDK's `Unable to obtain OffsetTime ..
  {OffsetSeconds=n},ISO resolved to <time>`; the order is the instants', a time less its
  offset, then the times'; hash `time ^ offset`); `InstantSource` (`Clock` is one; the
  sources made are clocks in UTC, as a JDK's texts show: `FixedClock[..,Z]`,
  `SystemInstantSource`, a program's own given a zone `SourceClock[source,zone]`);
  `TemporalAdjusters` over the fields the documentation states; `datesUntil` (the n-th
  date is plus n times the months, then n times the days; the count is estimated by
  146,097 days in 4,800 months and settled by the dates beside it, so
  `MIN.datesUntil(MAX, P1M).count()` runs no date); `OffsetDateTime.timeLineOrder()`
  (the instants alone); `Year.isValidMonthDay` / `atMonthDay`; `getDisplayName` of
  `Month`, `DayOfWeek` and `Era` (a formatter's words), of `ChronoField` (CLDR's names of
  eight fields, the root's capitalized) and of a field of weeks (`week`).
- **The calendar**: `Chronology`, `IsoChronology` (`AbstractChronology` and `resolveDate`
  came with k70), `Era`, `IsoEra`, `ChronoPeriod` (`Period` is one: its class
  object costs `Booking.java` 459 B). Read off the answers: `ChronoLocalDate.from` of a
  value of no calendar names `class java.time.LocalTime`; `epochSecond` refuses the year,
  the month, the day of the month, the hour, the minute, the second, then the date;
  `IsoChronology.hashCode` under a JDK is a class's identity (it moves with
  `-Xshare:off`), so ours hashes as an object.
- **Who pays: the calendar.** `calendars()` (in `Iso`, `TemporalQueries`, `Parsed`) is
  answered by whether the program reaches `TemporalQueries.chronology()`
  (`JavaLowering.reach` notes the first reach and lowers again); every class answers the
  question behind it, and the question object is made only then
  (`TemporalQueries.Calendar`). Measured: answered in every class unasked, a program
  writing by a pattern grew 1.3 KB (`IsoChronology` made through the dispatch of
  `query`); behind the question nothing. A formatter holds its calendar in one field
  (ISO's for the ISO forms of a date).
- **`IsoFields`, `JulianFields`, `WeekFields`** written from the definitions (ISO-8601's
  week 1 holds January 4; a locale's week 1 is the first week of the period holding
  `minimalDays` of its days, or the week before it where the days before its start
  are that many): one formula over a day's index in its period and its day of the week
  (`WeekFields.weekOne`). Compared with a JDK: all 49 `WeekFields` over 382 seeded dates
  (values, ranges, `with`), every field of the three classes on the other classes. The
  refusals name what a JDK reads first (`Unsupported field: DayOfWeek`, then
  `DayOfMonth` / `DayOfYear` / `Year`). `WeekFields.of(Locale)`: `UK` Monday and 4, the
  others Sunday and 1. **The todo's premise was wrong** (measured 2026-11-20, JDK 25.0.5):
  a JDK under `C.UTF-8` has the default locale `en`, whose weeks start on Sunday with
  one day, as `en_US`'s, `ROOT`'s and `en_CA`'s do, under `en_US.UTF-8` too; only `en_GB`
  differs.
- **Resolving a field that is no `ChronoField`** (`Parsed.resolveOthers`, behind
  `Parsed.others()`: whether the program makes a `TemporalField` class other than
  `ChronoField`): after the date's `ChronoField`s, each such field in the order read is
  asked `resolve(map, parsed, style)` over a map view of the arrays (`Parsed.Fields`); one
  that takes itself out or answers a date or a time is a change, after which the first
  is asked again, and a date's `ChronoField` it put is resolved as they are; fifty
  changes are `One of the parsed fields has an incorrectly implemented resolve method`.
  A time a field answers leaves the time fields to be checked against it (a half day
  alone is then no middle of it); the check compares such fields with the date or the
  time too; two dates are `two different dates`, two times `different times`. The week
  fields' rules, read off the grid: a localized day of the week is checked from 1 to 7 in
  every style and overwrites `DAY_OF_WEEK`; a week-based year resolves only where no
  `YEAR` is among the fields; strictly a date of another month or year is rejected;
  smartly a week of a month or a year is checked against the field's range, of a
  week-based year against that year's weeks. A JDK resolves them in the order of a hash
  map of identity hashes, which changes from run to run (user docs).
- **The letters**: `Y`, `w`, `W`, `e` and `c` of one or two (`Step.Localized`: a number
  of the formatter's locale's field, made where it is written or read, one of a run as
  any number; `Localized(..)` in the text); `Q`, `q` (`IsoFields.QUARTER_OF_YEAR`; the
  words `1st quarter`, `Q1`, `1`, the root's full one `Q1` and short standalone one `1`);
  `g`; `ISO_WEEK_DATE`. **Who pays: the letters a pattern names.** `ofPattern` and
  `appendPattern` are natives whose emitters note the letters of a constant pattern
  outside its quotes, all of them for a computed one (`JavaLowering.namesPattern`, the
  front end's own calls not), then call the old bodies (`patterned`, `appended`);
  `Patterns.weeks()`, `quarters()`, `julian()` answer by them, `Texts.quarters()` by
  whether the program makes `IsoFields`' fields.
- **Runs of numbers** (k43 did not follow a JDK; 1,760 lines of `Runs`): leniently a
  number of no fixed width reads one to nine digits and those it keeps for the numbers
  after it, where k43 read 19; a run's fixed-width first and a follower read their
  widths; a number of no fixed width appended right after a run ends it, and its first
  then reads its own widths keeping none (`Step.Digits.ended`); a fraction of a fixed
  count and no point is one of a run, after a number or beginning one (then it keeps
  none and the numbers after it read their widths); a fraction at the text's end makes
  no field. `uuuuMMddHHmmssSSS` reads `20260307123456789`, which k43 refused.
- **A type argument checked too early** (`JavaClasses.complete`): a declaration read
  while another is completed checked its type arguments before the other's supertypes
  were set, so `LocalDate` (implementing `ChronoLocalDate`, whose members now reach a
  class implementing `ChronoZonedDateTime<LocalDate>`) was "not within bounds"; the
  checks now wait for the outermost declaration. A program of several files could hit
  it the same way.
- **What a program pays** (wasm, the artefacts' table): every program of k35, k42 and
  k43's tables the same bytes, 20 fewer or as above; a date written by a pattern 323,000
  -> 324,879, read 428,993 -> 431,456 (the run model and the formatter's calendar); an ISO
  form 3.3 KB smaller (the builder made a reduced number in every program to copy a
  run's follower); a pattern computed where the program runs 429,380 -> 495,505: it can
  name every letter (`WeekFields` 21.4 KB, `IsoFields` 12.6 KB, the top level 29.6 KB),
  and so does YAVI's `isoLocalDate()` constraint, whose `localDate(pattern)` passes its
  pattern on (k43's `yavi/Event.java` 946,257 -> 1,020,590, its four lines the same).
  `MonthDay.parse` 153,734, `OffsetTime.parse` 145,010, a date by `"YYYY-ww-e"` written
  370,960 and read 491,365, by `ISO_WEEK_DATE` read 402,801.
- **The grid** (`Grid.java`, 4,561 lines): the interpreter (26 s), the JVM (2.6 s), wasm
  (0.9 s, 1,172,798 B) and the component (1.2 s) print a JDK's lines (the JDK 2.4 s);
  the k35 and k43 grids print theirs as before.
- Not there: CLDR's `B`, `z`, `v` and localized forms came with k57 ("java.time: the names
  in words, the localized forms and a day's periods"); the rest is `.todo/k70`.
- Pins: java-spec `weeks-quarters-a-month-day-an-offset-time-and-the-iso-calendar`
  (standalone, 94 lines); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`
  (the letters, the resolving and the calendar where a program names them);
  `JavaLoweringTest#aProgramOfSeveralFilesIsReadFromItsSourceTree` (the type argument).

## java.time: the names in words, the localized forms and a day's periods (2026-11-20, k57)

**Invariant: a formatter writes and reads a zone's name in words, a localized form and a
period of the day as a JDK 25 does in the five locales `Locale` has, among the zones the
program has; a program pays for the names of the regions it holds, and for each of the
three only where it can make the part that writes it.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md). The grid and the sizes:
`.todo/artefacts/k57-java-time-what-k52-left/`.

- **The localized forms** (`ofLocalizedDate`, `ofLocalizedTime`, `ofLocalizedDateTime` of
  one style or two, `appendLocalized`, `getLocalizedDateTimePattern`): the patterns of the
  four kinds of locale (`Texts.kind`: the root, English, British, Canadian; `ENGLISH` and
  `US` alike) as a JDK answers `getLocalizedDateTimePattern`, a date and a time joined by a
  space in the root's and by `, ` in English, U+202F before `a`. `Step.Styled` makes a
  kind's parts from its pattern the first time it writes or reads (`Localized(MEDIUM,)` in
  the text). A formatter's calendar shows in the refusal of a value with no date
  (`Unable to extract ZoneId from temporal 12:00 with chronology ISO`: `Writing.shown`).
- **A zone's names** (`zone-names.txt`, generated by `JavaZoneRulesTest` from a running
  JDK's public `TimeZone`, `ZoneId.getDisplayName`, `DateFormatSymbols.getZoneStrings` and
  `DateTimeFormatter`; `JavaZoneNames` makes a program's two tables, `ZoneNames` of the
  library reads them): for each region of the tz database the names of standard time,
  daylight time and the zone as a whole, short and in full, in the four kinds; UTC's, GMT's
  and the fixed zones' are rules of `ZoneNames.fixed` the generator asserts; an offset, a
  prefixed offset (`UTC+09:00`) and `UT` are written as their ids, and so is `NARROW`. A
  name a JDK writes as `GMT-05:00` is the zone's offset now, not at the instant written
  (`GMT` for Abidjan in 1900), so it is a string of the table.
- **Which time's name is written** (a JDK's answers): of a value with an instant, its
  rules' `isDaylightSavings` but in 9 zones' spans, where a JDK writes the other name
  (Inuvik's summers of 1972 to 1978, and Edmonton, Vancouver, Winnipeg and their kin from
  their change of 2026-11-01 on): the generator asks the JDK at each change of the zone
  and halfway to the next, and the table holds the spans; of a local date-time, the zone as
  a whole in a gap or an overlap and else `isDaylightSavings` at its one offset, with no
  span; of a value with no date-time, the zone as a whole.
- **A JDK's names change with what it wrote before**: once it has written a zone's names
  for `ENGLISH`, it writes some of that zone's names for `UK` and `CANADA` as `ENGLISH` has
  them (148 zones, measured: `Abidjan Daylight Time` for `Abidjan Daylight Saving Time` in
  `CANADA`); the generator asks `ENGLISH` last and the grid writes it last (user docs).
- **Reading a name**: an offset, a prefix with or without one, then the longest of the
  names a JDK reads in the kind and the style (short for `z` and `v`, full for `zzzz` and
  `vvvv`) and of the ids the program has, a name beating an id as long; the ids are looked
  through once per reading (`ZoneName.longestId`, in either case where the reading ignores
  case), where asking `ZoneId.region` for each length read the program's table at every
  name it lacks (850 µs on wasm with 40 regions; the grid's names 18 s -> 1.75 s). A name
  is read as the zone a JDK reads it as, with the time it is a name of -- 0, 1 or 2,
  -1 for an id, shown in `Parsed`'s text --, which places an overlap's local date-time at
  the later offset for 0 and 2 and the earlier for 1. A JDK reads many names as another
  zone than the one writing them (`Accra Time` as Abidjan, `EET` in the root as the id
  `EET`, `British Summer Time` as Abidjan, and in British English `Accra Time` as London);
  a program carries the names a JDK reads as a region it holds and, where a JDK reads one
  of its regions' names as a region it does not hold, reads it as the first of its own that
  writes it (`JavaZoneNames.read`); a name a JDK reads as none is not read. Preferred zones
  take a name one of them writes. In the program's table a name read alike in several kinds
  is one entry, and a root city with its `(+0)` and `(+1)` one entry.
- **Found in `VV` on the way** (k43 did not follow a JDK): an offset past 18 hours after
  `UTC`, `GMT` or `UT` refuses the zone at its start, where the prefix alone was read;
  `z` is `Z` and an id is read in either case where the reading ignores case.
- **A day's period** (`B`, `appendDayPeriodText`; `Texts.periods`, `Texts.periodWords`):
  the root's `AM` and `PM`; English's midnight and noon (a minute each, written before the
  span they lie in), the morning, afternoon, evening and night, Canadian narrow words of its
  own. Read: the longest of the style's words and its halves of a day (`AM`), leniently of
  every style's. Resolved (a JDK's answers): with the hour of a half of the day and no half,
  the half in which the hour (mod 12) and its minute lie in the period, the first where in
  neither; alone (no field of a time) its middle, strictly nothing; with an hour of the day,
  that hour and minute checked against the period before the hour is (`Conflict found:
  Resolved time 24:00 conflicts with DayPeriod(12:00-24:00)`; midnight holds 24:00),
  leniently not; the last period read wins; a half read with it is checked against the
  middle.
- **`localizedBy`**: the locale and ISO's calendar (the same formatter where both are its
  own); no locale `Locale` has names a calendar, a zone or digits. Its null refusal is a
  JDK's helpful message.
- **Who pays.** The pattern letters `z`, `v` (`Patterns.zones()`) and `B`
  (`Patterns.periods()`) make their parts only where a pattern can name them; a localized
  form's time notes `z` unless its style is short, medium or null (`JavaIntrinsics`
  natives of `ofLocalizedTime`, `ofLocalizedDateTime`, `appendLocalized`). `Parsed.periods()`
  and `Parsed.zoneTexts()` / `Step.zoneTexts()` (whether the program makes `Step.DayPeriod`
  or `Step.ZoneText`) keep resolving a period and reading names out of every other
  program; `Step.regional()` keeps the ids of the regions held out of one holding none.
  The tables (`ZoneNames.written0`, `read0`) hold the regions held: Tokyo 124 and 100 B,
  Paris 159 and 510, every region 90,405 and 26,798 B. Measured and changed: a `HashMap`
  of the names put every class's `hashCode` and `equals` in a program writing a zone's name
  (49 KB, now arrays); the patterns and the periods as array literals cost 16 KB of top
  level (now texts); `Long.parseLong` 3.7 KB (now a loop).
- **What a program pays** (wasm, the artefacts' table): a program making no formatter, or
  one holding a region and writing none, the same bytes; a date written by a pattern +1.9
  KB, read +6.3 KB; a computed pattern 495,472 -> 532,923 (`.todo/k70`); a date by
  `ofLocalizedDate(MEDIUM)` 332,031; Tokyo by `"... z"` 409,986, read 527,968; a time by
  `"h:mm B"` 330,931.
- **The grid** (`Grid.java`, 2,350 lines): the interpreter (63 s), the JVM (5.7 s), wasm
  (5.6 s, 1,199,318 B) and the component (5.5 s) print a JDK's lines (the JDK 3.7 s, all on
  a loaded machine); the k35, k42, k43 and k52 grids print theirs as before.
- What was not there came with k70 ("java.time: what k57 left") and k77 ("java.time: the
  localized pattern of a requested template"), but the cost leads (`.todo/k79`).
- Pins: java-spec `localized-forms-a-zones-name-in-words-and-a-days-period` (standalone,
  47 lines, 66 since k77's templates); `JavaZoneRulesTest` (the shipped names are the JDK's; a program's tables hold
  what it holds); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`
  (the letters, a localized form's time).

## java.time: what k57 left (2026-11-21, k70)

**Invariant: a formatter writes and reads numbers in the characters of its `DecimalStyle`,
reads from a `ParsePosition` resolved or not, serves as a `java.text.Format` and cuts the
fields it read to the ones it resolves; `IsoChronology` and a program's `AbstractChronology`
make a date of a map of fields -- all as a JDK 25 answers; a program pays for each only where
it reaches the member that makes it.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md). The grid and the sizes:
`.todo/artefacts/k70-java-time-what-k57-left/`.

- **A decimal style** (`DecimalStyle`, `withDecimalStyle`): each locale `Locale` has answers
  `STANDARD` itself (`DecimalStyle[0+-.]`; the hash is the four characters added up, 182);
  `getAvailableLocales` is a new set of the five. Written in the style's digits counted from
  its zero, its signs and its separator: a number (a value, a reduced one, a week of the
  locale's, the number of a word with none) and a fraction, the pad character not; an
  offset, a localized offset, an instant, a zone and a literal in ASCII. Read: the style's
  digits alone (not ASCII, not another case), the positive sign tested first (one character
  for both signs is positive, a digit as a sign is a sign), an instant in the style though
  written in ASCII. `localizedBy` takes the locale's style, `withLocale` keeps the
  formatter's. The formatter holds null for the standard one; `Writing.localize` rewrites
  what a number wrote, `Reading.numeral` reads a digit.
- **A reading from a position** (`parse(text, ParsePosition)`, `parseUnresolved`): an index
  outside `0..length` is `StringIndexOutOfBoundsException` (`Index 30 out of bounds for length
  19`); a failure notes the error index and leaves the index, a success moves it, the rest of
  the text unread. Unresolved, a `Parsed` shows `null` for its calendar and answers none to
  `chronology()`. `parse` at a position refuses where the position already held an error
  index too, throws every index error as it is, a part's too, and wraps anything else; the
  other `parse` members wrap an index error as well.
- **A formatter as a `java.text.Format`** (`toFormat`, `DateTimeFormatter$ClassicFormat`):
  `format` refuses a value that is no `TemporalAccessor`, wraps the formatter's refusal in an
  `IllegalArgumentException` with its cause and sets the `FieldPosition` to 0 and 0;
  `parseObject(String)` answers the resolved `Parsed`, or a query's value. Without a query a
  refusal of where the text stops or goes on is a `ParseException` at its index and any other
  (a part's, the resolving's) one at 0 with the cause; with a query the parse's refusal at its
  index. `parseObject(String, ParsePosition)` answers null for whatever a reading or the
  resolving throws, making the error index 0 where it is none (a null position is the
  helpful NPE of `getErrorIndex`). Read off the answers: `ParsePosition`'s hash
  `(errorIndex << 16) | index`, `FieldPosition`'s `(field << 24) | (begin << 16) | end` (the
  attribute not in it), `Format.parseObject(String)`'s `Format.parseObject(String) failed`
  at the error index. No `clone`: the subset has no `Cloneable` (`.todo/k16`).
- **The fields resolved** (`withResolverFields`): the fields read are cut to those given
  before anything is resolved; null for all, an empty array for none, a null kept; the same
  fields answer the same formatter. They are an array, each field once (`Parsed.among`, by
  `equals`); the set `getResolverFields` answers is made the first time it is asked. Measured:
  a `HashSet` held put every class's `hashCode` in the program, 449,446 B of wasm against
  415,823.
- **The date fields over a map** (`Chronology.resolveDate`, `AbstractChronology`, and
  `IsoChronology` now one): the documented combinations in the documented order -- an epoch
  day first, its other fields left to be checked; a proleptic month; a year of an era; then a
  year with a month and a day, a week of a month, a day of the year, a week of the year --
  each field taken out of the map as it is checked, so what is left after a refusal is a
  JDK's. ISO's answers two things otherwise than the generic one: an era of another value is
  `Invalid value for era: 2` (the generic `Invalid value for Era (valid values 0 - 1): 2`),
  and leniently a proleptic month is not checked. **`Parsed` keeps its own copy over its
  arrays**: through the map, a program reading a date by a pattern was 56 KB larger (403,287
  -> 459,364 B), so the algorithm is written twice and the grid holds both to a JDK.
- **Leniently, a day of the week in an aligned week is the day before the one asked**
  (`AbstractChronology.leniently`, `Parsed.weekDay`): from the week's start, the next or same
  day of the week `d - 1`, a week further for each seven past the last, and `Invalid value for
  DayOfWeek: 0` where `(d - 1 + 6)` truncated modulo seven is none (from `d = -6` down, every
  seventh). 136,518 answers of 136,518 asked over years, months, weeks and days. k52's probe
  "found no one rule" because its rule was the day of the calendar's week a day earlier, which
  agrees only where the day lies in the start's week. Strictly and smartly, the day itself.
- **The era `CE` leniently in English** is 1, the root's word; `BCE` is not read (`BC` is).
- **A localized offset is read as a JDK reads it**: no part checked against its range
  (`GMT+25:00`, `GMT+01:60` read); in full the hours and the minutes are two digits each, both
  characters of a pair read before either is tested, so a text ending inside them is
  `StringIndexOutOfBoundsException` at the text's length (`GMT+1` 5, `GMT+01:` 7, `GMT+x` 5;
  within a pad, the pad's end); the seconds only where they stand whole; short, an hour of one
  digit or two and the minutes and seconds where they stand. k43 refused such a text at its
  start.
- **Asked and unchanged**: a fraction of no fixed width right after a run does not end it,
  its first keeping the fixed widths' digits; the clocks' `equals` and `hashCode` (a system
  clock's zone + 1, a fixed one's instant ^ zone, an offset one's clock ^ duration, a ticking
  one's clock ^ the tick's nanoseconds) were a JDK's already, `InstantSource.system()`'s is an
  object's.
- **The null refusals** of the formatter and the builder, of every class's `from`, of the
  adjusters of a day of the week, `WeekFields.of` and ISO's calendar are a JDK's words, the
  helpful ones (`Cannot invoke "java.time.DayOfWeek.getValue()" because "dayOfWeek" is null`)
  written out; `appendGenericZoneText(style, null)` is no preferred zones.
- **A calendar of the program's own**: the members a JDK's `Chronology` has as defaults are
  defaults here (`date` and `dateYearDay` of an era, `dateNow`, `epochSecond`, which refuses the
  offset, the time, then the date); its date-times, zoned date-times and periods came with k78
  ("java.time: a calendar of the program's own").
- **Who pays** (`JavaIntrinsics.REACHES`, `JavaLowering.reaches`: a question answered by
  whether the program reaches a member, the first reach lowering it again):
  `DateTimeFormatter.decimals()` by `withDecimalStyle`, `Parsed.filtered()` by
  `withResolverFields`, `Parsed.lenients()` by `withResolverStyle` -- the one way to a lenient
  formatter, behind which only the lenient week rule is so far (`.todo/k79`) -- and
  `Patterns.localizedOffsets()` by the letters `O` and `Z` (`GmtOffset`, 3.7 KB, was in every
  program of a pattern).
- **What a program pays** (wasm, base `358f6b493` -> the k70 commit `ca6c72ecb`, before
  merging k64's arrays, which made the grid 75 KB smaller): `LocalDate.parse` of a text,
  `ZonedDateTime` in UTC and in Tokyo, `plusDays` the same bytes; a date written by a pattern
  306,048 -> 304,922, read 403,287 -> 402,498; a pattern computed where the program runs,
  written and read, 508,946 -> 512,488; `ISO_LOCAL_DATE_TIME.format` 270,570 -> 271,385,
  `LocalDate.parse(.., ISO_LOCAL_DATE)` 341,894 -> 342,408, `ZonedDateTime.parse` of
  `ISO_ZONED_DATE_TIME` in Tokyo 421,883 -> 423,262; YAVI's `Member.java` and `Booking.java`
  21 and 23 B more, k43's `yavi/Event.java` 989,578 -> 994,489 (it passes a pattern on, so it
  names every letter and reaches `withResolverStyle`). The new members: a decimal style
  309,258, `toFormat` 320,157, a reading from a position 402,793, `withResolverFields`
  415,823.
- **The grid** (`Grid.java`, 998 lines): the interpreter (85 s), the JVM (6 s, a 379,403 B
  jar), wasm (5 s, 1,084,939 B) and the component (5 s) print a JDK's lines (the JDK 3 s); the
  k35, k43, k52 and k57 grids print theirs as before.
- Not there: a zone's name written as an offset is the one of the table's making:
  `.todo/k80`. The localized pattern of a requested template came with k77 ("java.time: the
  localized pattern of a requested template").
- Pins: java-spec `a-formatters-decimal-style-a-reading-from-a-position-the-fields-it-resolves-and-a-calendars-map`
  (standalone, 54 lines); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`
  (the four questions).

## java.time: the localized pattern of a requested template (2026-11-21, k77)

**Invariant: `ofLocalizedPattern`, `appendLocalized(String)` and
`getLocalizedDateTimePattern(String, ..)` find the pattern a JDK 25 answers for a requested
template in the five locales `Locale` has and refuse what it refuses; a program pays for the
skeletons and their matching only where it calls one of them, and for a week's, a quarter's,
a period's or a zone's parts only where a template can name one.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md). The grids and the sizes:
`.todo/artefacts/k77-java-time-the-localized-pattern-of-a-requested-template/`.

- **The rule, read off a JDK's answers** (2026-11-21, JDK 25.0.5; every date template of
  bounded counts, every time template, every resolving date before every time, 2.5 million
  seeded and mutated templates, in each kind of locale and in `en-001`: a model of the rule
  below gave every one of some sixteen million answers):
  - **Valid**: the letters in the documented order, at most the documented counts (G 5, y
    any, Q 5, M 5, w any, E 5, d 2, B 5, two of h H j C, m 2, s 2, four of v z).
  - **j and C** are the locale's hour: `H` in the root and British, `h` in English and
    Canadian. Beside a `B` they are `h`, and a British template is then matched among
    international English's skeletons (`en_001`: `MEd` is `E, dd/MM`, `GyMd` `d/M/y G`, no
    skeleton of `EEEE`; all 108,864 date parts before `Bj` were answered so); the root's has
    no such set, so its `Bj` has no pattern.
  - **The skeleton**: of the template's letters, each as many as the template has but the
    month's and the day of the week's; among those, the nearest by the month's width, then by
    the day's, where one or two digits of a month are one width and one to three letters of
    a day one (no two skeletons tied). **No width is adjusted**: `yMMMMEEEEd` is `y MMM d, E`
    in the root and `E, MMM d, y` in English; British English has that skeleton,
    `EEEE, d MMMM y`. This is not UTS #35's matching.
  - **None**: the template is cut before its first letter of a time (B h H m s v z), each
    part matched alone, and the two joined as a localized form joins them (a space in the
    root's, `, ` in English); a part with none is a refusal, `DateTimeException: Requested
    template "s" cannot be resolved in the locale "en_US"` (the locale's `toString`).
  - **The skeletons**: 56 in the root's, 56 English, 64 British, 57 Canadian, 57
    international English; the dates (with the generic calendar's of `yyyy`: `yyyy` is
    `G y` in the root's, `y G` in English), the times (none of `z`, so a template of `z`
    has no pattern), the day of the week before a time (`EHm`, `EBhm`). `m`, `s`, `QQQ`,
    `yy`, `hh` and `jj` alone have none.
  - **Refusals**: `getLocalizedDateTimePattern` checks the template, the calendar, then the
    locale for null, then the order: `Requested template "dM" is invalid.`, and of a template
    holding j or C `... is invalid, which translated into "HHdC" after the 'j' or 'C'
    substitution.` (every j the locale's hour; the first run of C as many of the hour as the
    template has C). `ofLocalizedPattern` and `appendLocalized` refuse at once,
    `Requested template is invalid: jjj`.
- **A formatter of a template** (`Step.Templated`, `Localized(yMMMd)` in its text): ISO's
  calendar, `SMART`, the default locale; the pattern is found where it writes or reads, in
  its locale, and made into parts once per kind of locale. A template the locale has none for
  refuses there, inside an optional part too, and where read as a part's refusal.
- **A calendar of no type**: `getCalendarType` of a program's own calendar may answer null,
  and a JDK refuses it where it looks up a locale's patterns, with its words:
  `Cannot invoke "String.equals(Object)" because "calType" is null` for a template,
  `"requestID"` for a localized form's styles (k57's ignored it). Written: the formatter's
  calendar, else the value's; read: the formatter's (`Texts.typed`).
- **The data** (`Texts.skeletons`, CLDR's as a JDK answers them, under the Unicode entry of
  `THIRD-PARTY-NOTICES.txt`): five sets, each holding what differs from the set it adds to
  (root, English over it, international English over English, British over international
  English, Canadian over English), 1,829 characters; a set's skeletons of one first letter
  stand together, and a lookup reads those alone. A newer JDK: the spec case's JDK leg fails
  first; `Every.java`'s lines under it against the build's say which skeletons moved.
- **Who pays**: `ofLocalizedPattern` and `appendLocalized(String)` are natives
  (`JavaIntrinsics.templateLetters`): a constant template names `w` and `Y` for its `w`, and
  its `Q`, `B` and `v`; a computed one all five. A template's other letters find patterns of
  letters every formatter has (`ccc` is no week's). `Texts.ownCalendars()` (`makesOther`: a
  `Chronology` that is not ISO's) keeps the calendar's check, and `Step.templated()` (the
  part made) the formatter's locale handed to the parts (`Writing.locale`,
  `Reading.locale`), out of every other program. Measured: handed always, a program writing
  by a pattern grew 249 B and one reading 362 B; behind the question 106 and 146, the
  fields' own.
- **What a program pays** (wasm, base `e979405d1`): a program making no formatter, a zoned
  date-time in Tokyo, YAVI's `Member.java` and `Booking.java` the same bytes; a date written
  by a pattern 281,528 -> 281,634, read 358,746 -> 358,892, a computed pattern 449,750 ->
  449,982, `ISO_LOCAL_DATE_TIME.format` 251,279 -> 251,361, `ofLocalizedDate(MEDIUM)` written
  286,116 -> 286,198 and read 363,332 -> 363,478, k43's `yavi/Event.java` 921,054 -> 921,258
  (the two fields). The new members: a date written by `ofLocalizedPattern("yMMMd")` 293,334
  (7.1 KB over the style's: the matching 5.7 KB, the skeletons 1.4 KB), read 370,643, by
  `"yw"` 320,156, a template computed where the program runs, written and read, 455,387,
  `getLocalizedDateTimePattern` of a template alone 113,704. Measured and changed:
  `String.indexOf(String)` and `replace(char, char)` brought 6.2 KB of the run time (`SEARCH`
  2.7, `%JAVA-REPLACE-CHAR` 1.9, `%SEQ-TO-LIST` 1.4): the text is walked by characters, and a
  set's own skeleton hides its parent's by being walked first, no list of the keys seen.
  **Not taken: an index of where each set's first letters start** (made once): a time's
  lookup on wasm 14 µs against 130 by the walk, `yMMMEd` 108 against 132, on the interpreter
  0.25 ms against 1.1; it cost every program of a template 1,066 B, and a formatter looks its
  template up once per kind of locale. Nor the times first in a set: a time's lookup 92 µs,
  a date's of `y` 166.
- **The grids**: `Grid.java` (737 lines: a bounded grid of dates, times and the two joined,
  refusals, seeded templates; formatters of 31 templates written over nine values in the five
  locales and read back; the refusals of a formatter; a calendar of the probe's own): the
  interpreter (146 s), the JVM (15 s), wasm (26 s, 636,496 B) and the component (26 s) print
  a JDK's lines (the JDK 8 s). `Every.java` (5,899 lines: every date of bounded counts, every
  time, every resolving date before every resolving time, 80,000 seeded): the interpreter
  (17 min), the JVM (31 s), wasm (113 s, 190,387 B) and the component (120 s) print a
  JDK's (80 s); the k35, k43, k52, k57 and k70 grids print theirs as before on the four. A
  lookup walks a set's skeletons to those of the template's first letter: the 108,864 dates
  of one locale 3.5 s on the JVM, 39 s under a JDK.
- Pins: java-spec `localized-forms-a-zones-name-in-words-and-a-days-period` (66 lines with
  the templates); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`
  (a template's letters, the calendar's check where no calendar of the program's own is).

## java.time: a calendar of the program's own (2026-11-21, k78)

**Invariant: a calendar of the program's own has the date-time, the zoned date-time and the
period a JDK 25 gives it, its dates the defaults, and a formatter of it writes and reads in it, as
a JDK answers; a program pays for any of it only where it makes a `Chronology` that is not ISO's,
and for the calendar's zoned date-time where it writes only where it can give a formatter a
zone.** Provenance: [java-frontend-provenance.md](java-frontend-provenance.md). The probes and the
sizes: `.todo/artefacts/k78-java-time-a-calendar-of-the-program-s-own/`.

- **The todo's premise was half wrong** (measured 2026-11-21, JDK 25.0.5): `localDateTime` of a
  calendar whose dates are `LocalDate` is ISO's `LocalDateTime` (the date's `atTime`); the internal
  `ClassCastException` is `zonedDateTime`'s, of both kinds, where the calendar's date-time is made
  from ISO's and cast.
- **The calendar's values** (`java.time.chrono`, package-private, the names a JDK's
  `getClass()` and refused casts show): `ChronoLocalDateTimeImpl` (a date of the calendar and a
  `LocalTime`; `date.atTime`), `ChronoZonedDateTimeImpl` (that, an offset, a zone),
  `ChronoPeriodImpl`. `Chronology.localDateTime` is the value's date in the calendar at its
  `LocalTime`, `zonedDateTime` its zone (or offset), then its instant or, with none, its
  date-time placed; each wraps a `DateTimeException` in `Unable to obtain ChronoLocalDateTime
  (ChronoZonedDateTime) from TemporalAccessor: class ..`. Read off the answers:
  - a date-time's `ChronoField` of a date is the date's, of a time the time's (their refusals,
    `get`'s quoted and unquoted words, come from there); a time-based unit moves the time and
    carries whole days into the date in one step, half days in 256ths of the amount first (the day
    a refusal past the last names; ISO's `LocalDateTime` did it in halves and now does it so);
    `until` in a time-based unit is the days in the unit plus what the times differ by, each cut
    by itself (36,945,992,877,001 µs where ISO's date-times count ...000), in a date-based one the
    dates', the end's a day earlier where its time is earlier (both directions, where ISO's
    `LocalDateTime` also moves it a day later);
  - a zoned date-time moves its local date-time by every unit, a time-based one too, keeping the
    offset where it can (ISO's moves the instant: an hour after 02:30+02:00 in Paris on a night
    the clocks go back is 03:30+01:00 here, 02:30+01:00 there); `with(INSTANT_SECONDS)` adds the
    difference as seconds to the local date-time, `with(OFFSET_SECONDS)` places the local
    date-time's instant at that offset in the zone; `until` counts between the local date-times
    at this offset (115 days where ISO's, in the zone, count 116); the offset of a local date-time
    is the one `ZonedDateTime.ofLocal` finds for ISO's of the same day and time, a gap moving it
    later by the gap;
  - a period is added where its calendar is the value's or none (`Chronology mismatch, expected:
    Shifted, actual: ISO`); months as months and years as that many where the calendar's months
    of a year are a fixed count (its `range(MONTH_OF_YEAR)`), otherwise years, then months;
    another amount only where it is such a period of the same calendar (`Unable to obtain
    ChronoPeriod from TemporalAmount: class java.time.Period`, `ClassCastException: Chronology
    mismatch`); `normalized` by that count, unchanged with none; the text `Shifted P1Y2M3D`;
  - the hashes: a date-time's date ^ time, a zoned one's as `ZonedDateTime`'s, a period's ISO
    period's ^ the calendar's;
  - what a change made is checked: a date (`AbstractChronology.dated`) cast to `ChronoLocalDate`
    and its calendar compared, `Chronology mismatch, expected: X, actual: Y`; a date-time or a
    zoned date-time cast to the class above and compared, `required: X`; all
    `ClassCastException`s, a refused cast in a JDK's words (`(java.time.LocalTime and
    java.time.chrono.ChronoLocalDate are in module java.base of loader 'bootstrap')`,
    `AbstractChronology.castRefused`). A date given a date-time as an adjuster is checked as a
    date (`expected`).
- **The defaults** of `ChronoLocalDate`, `ChronoLocalDateTime`, `ChronoZonedDateTime` and
  `ChronoPeriod` are the documentation's: a date's era, leap year, length, epoch day, order (the
  epoch days, then the calendars: 10 between a `Shifted` and an ISO date of a day), adjusters,
  amounts and fields that are no `ChronoField`, a `ChronoField` or a `ChronoUnit` refused; a
  date-time's order is the dates' (so its calendar first), the times', then the calendars', where
  `isAfter`, `isBefore`, `isEqual` compare the days and times alone; a zoned one's the instants,
  the nanoseconds subtracted, the local date-times, the zones' ids (26 for Paris against
  `+01:00`), the calendars. `from` and `timeLineOrder` of the date-time and the zoned one are
  there. `LocalDate`, `LocalDateTime` and `ZonedDateTime` order a value of another calendar the
  same way and `Period` refuses one (`Chronology mismatch, expected: ISO`, `Period requires ISO
  chronology`), behind `Iso.ownCalendars()`; `IsoFields` has no field of a value of another
  calendar (`IsoFields.ownCalendars()`).
- **A formatter of a calendar** (`DateTimeFormatter.calendared`, `Parsed`, behind
  `Texts.ownCalendars()`): a calendar or a zone the value has already is none; a value with an
  instant is written as the zone's zoned date-time of it in the calendar (Thin's: the internal
  `ClassCastException`); a value with a date has it in the calendar, one with fields of a date but
  no date is refused (`Unable to apply override chronology 'Thin' because the temporal object
  being formatted contains date fields but does not represent a whole date: 2026-03`); the rest is
  written as it is, `with chronology Shifted` in a refusal's text where the calendar is not its
  own. A text's date is made by the calendar's `resolveDate` over a map of the fields (k70's
  `Fields`), and one of another calendar is refused (`ChronoLocalDate must use the effective
  parsed chronology: Thin`); an instant read is the calendar's zoned date-time of it; days a time
  passed (24:00, a lenient 25:00) are added as an ISO period, which the calendar's date refuses
  (`Chronology mismatch, expected: ISO, actual: Shifted`); `Parsed` shows `{..},Shifted resolved to
  Shifted 2126-3-7` and answers ISO's date to `localDate()`.
- **An era is written and read as a number in a calendar of another type** (`Texts.eraWords`):
  the words are a type `iso8601` or `gregory`'s, the value's (or none's) where the formatter names
  no calendar; no type is the refusal of k77 (`requestID`). A JDK has the words of five other
  types (`buddhist` `BE`, `japanese` `Meiji`, `roc` `Minguo`, `islamic-umalqura` `AH` and its
  months), which the library has not: `.todo/k92`. The months, days and halves of a day are the
  same words in every type.
- **A wildcard given is read as its capture** (`JavaTypes.contains`, JLS 5.1.10): a
  `ChronoLocalDateTime<?>` is a `ChronoLocalDateTime<? extends ChronoLocalDate>`, by the bound the
  class declares for the parameter (one naming no parameter of the class); the subset refused the
  library's own `localDateTime` until then.
- **Who pays**: `Iso.ownCalendars()`, `IsoFields.ownCalendars()` and k77's `Texts.ownCalendars()`
  (`JavaIntrinsics.OTHERS`: a `Chronology` made that is not ISO's); the impl classes and the
  interfaces' defaults are in a program only where a class of it inherits them;
  `DateTimeFormatter.zones()` (`REACHES`, `withZone`). Measured (wasm, base `0c33a3046`): the
  programs of k70's and k77's tables the same bytes or, reading a text, 83 to 138 B more (`Parsed`'s
  two fields); a program writing a date in its own calendar 293,985 B, reading one 435,109 (the
  calendar's `resolveDate` over a map: k70's 56 KB, which ISO's dates still do not pay), its
  period and date-time printed 181,020 (ISO's 105,646). Changed by measuring: a static list of the
  date's fields was in every program with a formatter (1.7 KB), a loop over `ChronoField.values()`
  made all its constants (3 KB), the zoned date-time and the class objects of the refusals' texts
  (`getClass()` where `getClass().getName()` does) were in every program writing in a calendar of
  its own (30 KB together).
- **The probes** (`probes/`, 13 programs, 1,783 lines): the four backends print the same lines;
  where a JDK's differ is listed in the artefacts' README (helpful null messages, identity hashes,
  a map's order, `.todo/k92`, `.todo/k93`).
- Not there: the weeks, the Julian days, `appendChronologyId` / `appendChronologyText` and the
  other types' words of a calendar of the program's own (`.todo/k92`); an instant read beside a
  date or a time (`.todo/k93`, in ISO's calendar too).
- Pins: java-spec `a-formatters-decimal-style-a-reading-from-a-position-the-fields-it-resolves-and-a-calendars-map`
  (standalone, `calendarsOwn`: 33 lines more, with ISO's half days past the end);
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (`L17`, `L18`: the
  calendar's writing and reading where a program makes one, its zoned date-time where it can give
  a zone).

## java.util.Date and java.sql (2026-11-20, k37)

`java.util.Date` and `java.sql`'s `Date`, `Time`, `Timestamp` are Java source, written from
the API documentation; what it leaves open is a JDK 25's answers to a grid
(`.todo/artefacts/k37-java-util-date-and-the-java-sql-classes-a-value-is-tested-as/Grid.java`,
2,095 lines: 378 instants through every member, 73 texts of `java.sql.Date`, 40 of `Time`,
124 of `Timestamp`, conversions of `java.time` values over the calendar's change and the
long's ends, 300 seeded date-times, the members of each against the others).

- **`java.sql` is the first package of the library outside `java.base`.** It is not in
  `LIBRARY_PACKAGES`; `import module java.sql` brings `java.sql` and `javax.sql`
  (`JavaClasses.SQL_MODULE`). With it the library had its first two classes of one simple
  name, so the imports got a compiler's two levels: a package imported on demand shadows a
  module imported (the implicit `java.base` of a compact source file included), and two
  classes of the name brought by one level are `reference to Date is ambiguous` where the
  name is written (`JavaClasses.imported`; `find` takes the position). Before, the first
  package of one list won.
- **The calendar is `rontolisp.internal.Dates`**: Julian before 1582-10-15, Gregorian from
  then on, the year written as the year of its era (1 BC is `1`, the era lost, as a JDK
  writes it: `java.sql.Date.valueOf(LocalDate.of(0, 1, 1))` is `0001-01-01`). Our own
  formulation: one day count per calendar (`365 y + floor((y + 3) / 4)`, the Gregorian one
  less its centuries), the Julian 1582-10-04 the day before the Gregorian 1582-10-15.
  A wall time carries its seconds into the day first and THEN picks the calendar by the
  day (`1582-10-14 24:00:00` is the Gregorian 10-15, `1582-10-10` the Julian, so 10-20).
- **The zone is the host's at the instant**: `Dates.local0` is `%local-time-offset`,
  asked of every instant (clamped to the +-8.64e12 s every host answers), where
  `ZoneId.systemDefault()` keeps today's offset (k35). A JDK's `Date` reads its zone's
  rules at the instant, so this matches it where `java.time` cannot. A wall time takes
  the offset a day before or a day after, whichever holds; one the clock skips or shows
  twice takes the one that is not summer time (the dst bit): New York, London, Sydney and
  Lord Howe's 30-minute shift agree with a JDK at both changes of 2026. Under New York
  the grid differs from a JDK only in the zone's name, before 1883 (the host's local mean
  time, `-04:56:02`; a JDK's `TimeZone` reads EST) and past the year 275,760.
  `toString` names the zone `UTC` at offset 0 and `GMT+hh:mm` otherwise, what a JDK
  writes for a zone of no name; a JDK writes `JST`, `EST`, or `GMT` under `TZ=GMT`.
- **A text is read as a JDK reads it** (the grid): `Timestamp.valueOf` trims, splits at
  the first space, reads and range-checks the date before it looks at the time; the
  hours, minutes and seconds are any int (`25:61:61`, `-1:15:30`) and carry; a point at
  the end is refused before the seconds are read but after the hours; the fraction is 1
  to 9 characters starting with a digit. `java.sql.Date.valueOf` and `Time.valueOf` do
  not trim. A number is refused as `Integer.parseInt` of a RANGE refuses it (`Error at
  index 1 in: "1x"`, `Dates.parse`), not as of a string.
- **`Timestamp` keeps the whole seconds in `Date`'s milliseconds and the rest in
  `nanos`**; `getTime` answers both, which is what `Date`'s `hashCode`, `equals`,
  `before` and `after` read, so `date.equals(timestamp)` can be true and the converse
  not. Arithmetic past the long's ends wraps as a JDK's does (`new Timestamp(Long.MIN_VALUE)`).
- Not there, refused by name: `clone` (the subset has no `Cloneable`), the members
  deprecated since JDK 1.1 (`getYear` .. , the constructors of fields), `Date.parse`,
  `toGMTString`. A non-ASCII digit is read on the interpreter and the JVM only, as
  `Integer.parseInt`'s.
- **What a program pays** (wasm): `new Date(0).getTime()` 20,416 B, its `toString`
  149,761, `Timestamp.valueOf(..)` printed 206,473.
- **YAVI**: the schema example compiles from the unpatched coordinates (`patch.sh` has
  no edit left) and prints a JDK's 23 lines on the four backends: wasm 1,216,108 B
  (1,215,291 with the edit), the component 1,220,478, the jar 439,998. `shelf/Row.java`
  beside the grid -- a row of `java.sql` values read by a schema of the `java.time`
  classes, `UntypedInput.SqlTypes`' conversions and mismatches -- prints a JDK's 28 lines
  on the four: wasm 1,272,912 B.
- Pins: java-spec `a-date-is-milliseconds-and-java-sql-reads-and-writes-wall-times` (its
  lines hold in any zone: a `Date`'s text is printed without the zone's name), the
  `java.sql.*` line of `a-compact-source-file-starts-at-its-instance-main` (the shadowing),
  `JavaLoweringTest#anErrorTheLoweringDependsOnIsReportedAtItsPosition` (the two
  ambiguities).

## java.io (2026-11-17, j79)

**Invariant: a stream is Java source over `byte[]`, `char[]` and the stream under it; the
host is reached at three classes only -- `StandardOutput`, `StandardInput`
(`java.io`, not public) and the UTF-8 pair of `java.lisp` -- and a program that only prints
holds none of it.** No files: `File`, `FileReader`, `java.nio.file` stay refused.

- **The classes**: `InputStream`/`OutputStream`, the `ByteArray` pair, the `Filter` pair,
  the `Buffered` pair, `DataInput`/`DataOutput` and their streams, `PrintStream`;
  `Reader`/`Writer`, `StringReader`/`StringWriter`, `InputStreamReader`/`OutputStreamWriter`,
  `BufferedReader`/`BufferedWriter`, `PrintWriter`; since j87 the four null streams
  (anonymous classes of `nullInputStream` .. `nullWriter`), `FilterReader`/`FilterWriter`,
  `CharArrayReader`/`CharArrayWriter`, `PushbackReader`/`PushbackInputStream`,
  `SequenceInputStream`, `LineNumberReader`; `Closeable`, `Flushable`; `IOException`,
  `EOFException`, `UTFDataFormatException`, `UnsupportedEncodingException`,
  `UncheckedIOException`; `java.nio.charset.Charset`/`StandardCharsets` (UTF-8, the default,
  ISO-8859-1, US-ASCII; since j98 UTF-16, UTF-16BE, UTF-16LE) with its two exceptions. `import module java.base` and a compact
  source file bring `java.io` and `java.nio.charset` (`LIBRARY_PACKAGES`; since j83 the three
  packages of `java.util.concurrent` too).
- **`System.out`, `System.err`, `System.in` are two things** (`JavaClass.Field.accessor`,
  `direct`). As the receiver of `print`/`println`/`flush`, and of a `printf`/`format` whose
  value is dropped, `System.out` is the host's stream (`JavaLowering.directReceiver`, the
  intrinsic class `rontolisp.internal.StandardStream`): `(write-line ..)`, as before. As a
  value it is `(java.io.StandardOutput.out())`, a `PrintStream` made at the first ask.
  Measured: `Fib`, `WrapInt`, `Sieve`, `WrapLong`, `Shapes`, `Basics` are byte-identical on
  wasm and the JVM. Rejected: `PrintStream` intrinsic with the host's stream as its value
  (j68: it is no `OutputStream`, so `new PrintWriter(System.out)` has no type) and `System`
  as source (every program would hold the class and its initialization). A method
  reference (`System.out::println`) and a static import of the field take the direct road
  too: `xs.forEach(System.out::println)` is 67,439 B of wasm either way, 71,120 through the
  object.
- **`StandardOutput` extends `PrintStream`** and writes its characters with `write-string`;
  a byte written to it is a byte of UTF-8, decoded as it comes. Both the direct forms and
  the object write to the host's stream with no buffer of their own, so their lines keep
  the order written. `close()` flushes and leaves it open (`deviations.md`).
- **`StandardInput`**: the host's standard input is a stream of characters
  (`%java-read-character`), handed out as the bytes of their UTF-8; an `InputStreamReader`
  over it takes the characters as they are (`unit`). A read of several goes on while
  `%java-input-more` says so: `listen` on the interpreter and the JVM, to the line's end on
  wasm, where `listen` of the standard input traps (measured, wasmtime 49) -- the two
  variants of `java.lisp`. So a program mixing a bulk `System.in.read(buf)` with another
  reader sees another split on wasm (`deviations.md`).
- **A `PrintStream` a program makes** encodes each print and writes it to the stream under
  it at once; an `IOException` is kept for `checkError()`, which is a `try`/`catch` in
  `PrintStream`, so making one costs the handler (below). `OutputStreamWriter` holds 8,192
  bytes and `BufferedWriter` 8,192 characters until `flush()`, as a JDK shows through the
  stream under them (10,000 ASCII characters: 8,192 bytes before the flush).
- **UTF-8**: `getBytes()` is `rontolisp:string-to-octets`; decoding is
  `%java-utf8-string`, not the host's `octets-to-string`, which takes what is no UTF-8 as it
  comes. The U+FFFD rules were measured against JDK 25 and pinned by 6,000 random sequences
  over the bytes that matter, identical through `new String` and through a reader: a byte
  starting nothing is one; a start whose second byte is outside its range (`E0`: `A0`..,
  `F0`: `90`.., `F4`: ..`8F`) is one, alone; a sequence cut by a byte or by the end is one;
  the three bytes of a surrogate are one. An `InputStreamReader` decodes a buffer at a time
  and keeps back the bytes of a character the buffer ends inside.
- **`LineNumberReader` counts as a JDK 25 does, measured** (j87; 1,900 random scripts of
  reads, skips, marks and resets over it and the three readers beside it, and a grid of
  scripts over ten inputs): `read()` gives a
  line's end as one `\n`, a read into an array gives the characters as they are and
  counts; the end of the input ends a line that was begun, once; a `skip` leaves no line
  begun (so the end after it counts nothing), and a `mark` does not keep whether one was;
  a mark set after a `\r` holds one character more than asked (the `\n` dropped is none
  of the limit). The reader under it is told of a `\r` it read as a line's end
  (`BufferedReader.afterCarriageReturn`).
- **A `BufferedReader` standing directly on `System.in` takes its line from the host**
  (j87; `Reader.hasLines` / `line`, which only `InputStreamReader` over the
  `StandardInput` answers, so a program with no such reader calls `Reader`'s own and
  holds nothing of it): `readLine` with nothing read ahead, no mark and no `\r` pending is
  `%java-readln`. A `\r` ending the host's line is its `\r\n`; one inside it ends a line
  of its own, and what follows goes into the buffer with the `\n` (`hostLine`). Measured,
  200,000 lines summed (mean of three): wasm 1.28 -> 1.06 s (`IO.readln` 0.89), the JVM
  0.62 -> 0.44 s; the program 143,022 -> 147,639 B of wasm, a `BufferedReader` over a
  `StringReader` 96,950 -> 98,374 B.
- **A charset is its codec, and a program holds the ones it names** (j98, 2026-11-18).
  `Charset` has the codec as methods (`bytes`, `text`, `unfinished` -- how many bytes at
  the end of a buffer wait for the next read --, `writing` / `reading` -- the charset
  the rest of an output or an input goes on in --, `lone`, `historicalName`); the class
  itself is UTF-8, ISO-8859-1 and US-ASCII. The UTF-16 ones are objects of
  `rontolisp.internal.Utf16`, a subclass: a call through `Charset` is direct while the
  program makes no `Utf16`, so their codec is in no other program. Three things keep
  it so. `StandardCharsets` is an INTRINSIC class whose fields are read through static
  methods (`Charset.utf8()` .., `Utf16.marked()` ..: `JavaClass.Field.accessor`, the
  road `System.out` takes), so naming `UTF_8` initializes nothing of UTF-16. A charset
  NAMED BY A CONSTANT is the charset: `Charset.forName` is `native` and lowered where
  it is called, and a call of the library whose member takes the name has the constant
  replaced by the charset when a member beside it takes a `Charset` in that place
  (`JavaLowering.charsetsNamed`: `getBytes("UTF-8")`, `new InputStreamReader(in,
  "UTF-8")`, `new PrintStream(out, true, "UTF-8")`; the names of
  `JavaIntrinsics.charsetNamed`, a subset of `Charset.find`'s). A name computed where
  the program runs, and an alias outside that list, is looked up among all of them.
  Measured (wasm): a `BufferedReader` over `System.in` 127,904 -> 128,313 B (j87's
  attempt: +18 KB); the same naming `"UTF-8"` three ways 143,937 -> 132,911 B (the
  lookup is gone); over `StandardCharsets.UTF_16` 135,246 B; `getBytes` of a computed
  name 64,129 -> 77,465 B.
  **UTF-16's rules, measured against JDK 25** (186 lines of a grid and 3,000 seeded
  sequences -- bytes through `new String` and through a reader given 1 to 4 bytes at a
  time, text through `getBytes` and through a writer in pieces --, identical on the
  four backends): UTF-16 writes the mark `FE FF` once, ahead of the first text that is
  not empty (a string's bytes, a writer's and a `PrintStream`'s first write), then the
  high byte first; it reads a mark at the start as the order of what follows and
  drops it, and reads the high byte first without one; a later `FE FF` or `FF FE` is a
  character. UTF-16BE and UTF-16LE write and read no mark. A surrogate with no partner
  is U+FFFD, written and read; a byte with no partner is one; a high surrogate
  followed by a unit that is no low one is ONE U+FFFD for both; a high surrogate
  followed by one byte, or by nothing, is one. So a reader keeps back an odd byte and
  a high surrogate ending its bytes -- the last of an odd run of them, each taking the
  unit after it (`Utf16.unfinished`; the first version kept any and split
  `D83D D83D DE00` otherwise than the JDK). The mark's state is no slot: a reader, a
  writer and a `PrintStream` swap their charset for the one the rest goes on in
  (`writing()` / `reading(..)`, two more `Utf16` objects named `UTF-16`), so
  `PrintStream.charset()` after the first print is another object of that name
  (`deviations.md`). A writer closed on a high surrogate writes the charset's `lone()`:
  `?`, U+FFFD in UTF-16.
- **UTF-16: written, measured and taken out** (j87; superseded by the entry above). `UTF_16`, `UTF_16BE`, `UTF_16LE`
  with a JDK 25's answers (the byte order mark written once and read once; a surrogate
  with no partner, a byte with none and a high surrogate with whatever unit follows it
  one U+FFFD each; 3,000 random sequences identical through `new String` and a reader
  fed a few bytes at a time) cost the `BufferedReader`-over-`System.in` program 143,022
  -> 161,075 B of wasm: `Support.encode` / `decode` and the two bridges choose the
  charset at run time, so every program with a reader or a named charset held the
  codec. What it needed first, done in j98: the UTF-16 charsets as objects of a
  class of their own, made only by a program that names them (a field read through a
  holder, as `System.out` is), the codec their method.
- **A writer holds the high surrogate a STRING ends in** (j87), as it held one an array
  ends in: `write("a\ud83d")` then `write("\ude00")` is the character's four bytes.
  Still open: a string holding a surrogate with no partner encodes to its three bytes
  where a JDK writes `?` (`%java-utf8-bytes` is the host's `string-to-octets`), and a
  string CONSTANT holding one is `?` on wasm. Measured 2026-11-18 (j98): the two halves
  of a pair appended to a `StringBuilder` one `char` after the other are a string of
  length 2 on the interpreter, 1 on the JVM (a JDK: 2 units, here one character), and
  on wasm no `equals` of the character they spell (`.todo/k82`).
- **`Double.longBitsToDouble`, `Float.intBitsToFloat`** are `%java-bits-binary` over
  `%java-nearest-binary` (exact, the subnormals included).
- **Measured** (2026-11-17, the box and method of "Measured" below; wasm / class bytes, then
  precompiled wasm / JVM seconds): a `ByteArrayOutputStream` written and read 74,674 /
  126,883; `BufferedReader(InputStreamReader(System.in))` summing 200,000 lines 142,695 /
  197,974, 1.04 / 0.59 s against 0.78 / 0.36 s for `IO.readln` (70,642 / 127,438);
  `PrintWriter(BufferedWriter(OutputStreamWriter(System.out)))` printing 200,000 lines
  244,022 / 260,052, 0.43 / 0.66 s against 0.50 / 0.42 s for one `StringBuilder` printed
  (76,122 / 108,409). The corpus case of the byte streams alone is 312,953 B of wasm, the
  one of the print streams and readers 460,101 B.

## java.util.stream (2026-11-19, k12)

**Invariant: a stream is a function answering its next element, each stage a closure over
the stage before it; nothing runs until the last operation pulls, and what a JDK 25 shows
of a pipeline -- which stage sees which element when, what `count()` skips, the order of
a collected map -- is what the corpus pins.** Written from the API documentation and
JDK 25's answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md));
the probes are `.todo/artefacts/k12-java-front-end-what-the-time-limits-and-duration-slice-left/`.

- **The shape** (`rontolisp.internal.Flow`, `ObjectFlow`, `IntFlow`, `LongFlow`,
  `DoubleFlow`: the one class behind each of `Stream`, `IntStream`, `LongStream`,
  `DoubleStream`, so a call of a stream's member is direct). A stage holds a `Pull` -- a
  functional interface of the package only lambdas implement, so a pull is a `funcall`
  with no choice among classes -- and an `Ending` the pull sets when it has no element
  (what it answers then does not count: no sentinel, so `null` is an element and an
  `int` needs no box). A stage handing on what the one before it gives (`map`, `filter`,
  `peek`, `limit`, `takeWhile`) shares that `Ending`; one going on past it (`sorted`,
  `flatMap`) has its own. The stage's state is a one-element array or a `Held` the
  lambda closes over. Rejected: `Iterator` classes a stage (two dispatched calls an
  element over every iterator class the program makes: the 50 to 65 ns an element
  measured for an enhanced `for` on wasm), a sentinel object (a cast to the element type
  a stage, a box an `int`), sinks pushed into (no `iterator()`, no lazy `flatMap`).
- **Laziness is the pull's**: an element goes through every stage before the next is
  taken, `limit` and `takeWhile` take no element past their last, `sorted` takes all at
  its first pull, `flatMap` asks an inner stream only as far as it is read, `iterator()`
  reads one element ahead at `hasNext` only. A collection's iterator is made at the
  first pull, so a stream sees what was added after it was made. All measured
  (`Lazy.java`), all a JDK 25's.
- **`count()` runs no stage where the size is known**, as a JDK 25: `Flow.Count`, a
  function asked when the stream ends, `null` where a source or a stage cannot tell.
  Known: every `Collection` but the concurrent ones (`ConcurrentHashMap`'s views,
  `ConcurrentLinkedQueue` / `Deque`, the blocking queues, `LinkedTransferQueue`, which
  override `stream()`), an array, `Stream.of`, `empty`, a builder, a range, `chars()`,
  an `Optional`, `concat` of two known. Kept by `map`, `peek`, `sorted`, `boxed`,
  `mapToObj`, `asLongStream`, `unordered`, `skip` and `limit` (which adjust it); lost by
  `filter`, `distinct`, `flatMap`, `takeWhile`, `dropWhile`; never known of `iterate`,
  `generate`, `lines()`, `splitAsStream`, `results`, a reader's `lines`. A grid of 114
  sources and stages by four questions (`Sized.java`); the corpus keeps 102 rows of it.
  One difference: a PARALLEL stream's `sorted().skip(n).count()` runs under a JDK.
- **A stream is asked once** (`Flow.link`: the JDK's `IllegalStateException` and its
  words); `parallel()` / `sequential()` / `onClose` answer the stream itself and
  `unordered()` a new one, as measured. The close handlers and the parallel flag are
  the pipeline's (`Flow.Origin`, shared from the source on): handlers run once, in the
  order given, the first failure thrown holding the others suppressed. **Everything
  runs in order on the calling thread**: `parallel()` only sets the flag, a combiner is
  never called (`deviations.md`).
- **A stage is told that no more is asked** (k26, 2026-11-20; `Flow.Told`, the chain
  `Origin.told`). A pull cannot know that the stage after it stopped, so the stages
  that hold something register a function of one `int` with the pipeline, each calling
  the one registered before it: `LEAVE` on the way up closes the stream a `flatMap`
  stage was reading (`Flow.within`; the stage made last first, an inner stream's own
  chain before its handlers: `close10 close1`, `close1 outer1`), `END` on the way back
  finishes a gatherer, the one nearest the source first, `BEGIN` makes the states of
  the gatherers before one. Who tells: `findFirst` / `findAny` once they hold an
  element, the three matches where they decide early, and `limit` (at the pull past
  its last element) and `takeWhile` (at the element that fails) with the chain as it
  was when they were made, which is the stages before them only -- a gatherer after a
  `limit` must not be finished as rejected. All a JDK 25's order, `Multi.java` of the
  k26 artefacts (50 lines). Left as measured: a later stage that throws closes nothing
  (a `try` / `finally` in every operation that ends a stream would put the exception
  machinery, 47 KB of wasm, in every stream program); `iterator()` and `Stream.concat`
  take an inner stream an element at a time, where a JDK takes it whole before it hands
  on the first (`close1 10`). A `flatMap` program pays 1,124 B of wasm for the chain
  (126,815 -> 127,939), one with no such stage a field of the pipeline's record.
- **`mapMulti` and a gatherer hold what they give for one element** (k26). A JDK pushes
  each element through the later stages while the function still runs; a pull hands on
  one element a call, so the stage keeps the rest in a list (`ObjectFlow.mapMulti`,
  `IntFlow.fed` and its kin, `rontolisp.internal.Gathering`) until it is asked again.
  What a program can tell: a `peek` after `mapMulti` sees an element's outputs after
  the function returned and none past a `limit`; a gatherer's `push` answers `true`,
  and `isRejecting()` `false`, until the pipeline said `LEAVE` -- a JDK's turn in the
  middle of an element once the later stages have enough (`push11=false!`), so a
  gatherer looping until `push` answers `false` does not return here. The elements
  handed on are a JDK's on every line of the probes. Rejected: stages that are pushed
  into (k12's reason: no `iterator()`, no lazy `flatMap`; both kinds of stage would be
  the library twice).
- **A gatherer** (k26; `Gatherer`, `Gathered`: one of four functions, as `Collected`;
  `Gatherers`). Each of these is a JDK 25's answer (`Gather.java`, `Gather2.java`):
  the state is made before the first element is asked for, of every gatherer of the
  pipeline in their order (`ainit binit`), and even where a `limit(0)` asks for
  nothing; an integrator answering `false` leaves the stages before it (`LEAVE`, `END`)
  and then finishes; the finisher always runs, told `isRejecting()` where the stream
  stopped asking. **A gatherer straight after a gatherer is one gatherer**
  (`ObjectFlow.gathering`, `Gathering.then`): `gather(a).gather(b)` prints what
  `gather(a.andThen(b))` prints, which is how `a`'s push comes to answer what `b`'s
  integrator did. `andThen` (`Gathered.both`): the first's push answers `false` once
  the second's integrator did, `isRejecting()` of what the first pushes into stays
  `false`, the finisher is the default one where both are, the combiner where either
  is, the initializer never. `Gatherers.mapConcurrent` starts a virtual thread an
  element, hands a result on when as many as its limit are under way (so a result
  waits for those before it and a task for a place: `[-1, 2, 3]` of `Gather2.java`) and
  at the end, waits for a mapping whatever interrupts the wait, and interrupts and
  waits for the others where one fails or the stream stops asking; a
  `RuntimeException` is thrown as it is, anything else inside a `RuntimeException`.
  A JDK may hand a finished result on sooner, by the clock. Sizes: a list through
  `windowFixed` 164,528 B of wasm, through `mapMulti` 145,142, through `map` 137,639.
- **A double stream's sum is Kahan's compensated one** (`Flow.add` / `total`: the sum,
  what the additions lost, and the plain sum, which is the answer where the first two
  are no number and it is infinite). Which summation a JDK uses is in no documentation:
  of plain, Kahan, Kahan with the last compensation subtracted and Neumaier, only the
  third gave a JDK 25's bits on all 20,000 seeded sums (`Sum.java`: 9,816 / 19,879 /
  20,000 / 15,002). `average`, `summaryStatistics`, `summingDouble`, `averagingDouble`
  share it; `min` / `max` are `Math.min` / `max` (`-0.0` under `0.0`, a NaN wins).
- **The collectors** (`Collectors`, `Collected`: a collector of four functions and a bit
  mask of marks). What the documentation leaves open and a program sees, each measured:
  `groupingBy` reaches its map by `computeIfAbsent` and `toMap` by `putIfAbsent` /
  `merge`, which is the order keys sharing a bucket are walked in (`1, 17, 33` in a
  table of 16); `partitioningBy` answers a map of `false` then `true` that takes no
  `put` (`Halves`); `toMap`'s `Duplicate key .. (attempted merging values .. and ..)`
  and `groupingBy`'s `element cannot be mapped to a null key`; `min` / `max` /
  `minBy` / `maxBy` keep the FIRST of equal elements; `characteristics()` of each
  collector and of each made of another (102 lines, `Ch.java`: `mapping` / `filtering`
  / `flatMapping` the downstream's, `collectingAndThen` those without
  `IDENTITY_FINISH`, `groupingBy` / `partitioningBy` only `IDENTITY_FINISH` where the
  downstream has it, `teeing` what both have without it). The set is made when asked
  for, in the enum's order. **Decided: the finisher is always called** and the marks of
  a collector of the program's own class are not read (`Collected.marks`): reading them
  is `characteristics()` of every collector made -- a set and a dispatcher in every
  program that groups -- for a mark only a `finisher()` that is no identity under
  `IDENTITY_FINISH` would show.
- **A string's streams** are `rontolisp.internal.Flows` (`chars` / `codePoints`: one
  stream, of code points; `lines`), members `JavaIntrinsics.meets` adds once
  `IntStream` / `Stream` is named; `CharSequence` has the two as defaults.
  `BufferedReader.lines`, `Matcher.results` (from where the matcher stands when the
  stream is READ, leaving it there: measured) and `Pattern.splitAsStream` (`split`'s
  pieces, one empty piece of an empty input) are iterators handed to `ObjectFlow.over`.
- **Measured** (2026-11-19, the box of "Measured" below, best of three; JVM class /
  precompiled wasm / JDK 25, ns an element over 1,000,000 elements, 20 rounds,
  `bench/Stages.java`): a loop over an `int[]` 5.3 / 16.0 / 1.3; `Arrays.stream(a).sum()`
  13.0 / 56.0 / 1.8; with one `map` 54.6 / 88.5 / 2.1; `map` and `filter` 70.7 / 123.5 /
  2.7; three `map` 86.5 / 156.4 / 8.0; an enhanced `for` over an `ArrayList<Integer>`
  29.5 / 54.8 / 2.7; `list.stream().mapToInt(..).sum()` 75.9 / 113.2 / 3.9;
  `filter(..).collect(toList())` 87.8 / 151.7 / 14.1. **A stage is some 33 ns an element
  on wasm and 16 to 40 on the JVM: a closure call's cost there** (j70 measured
  20,000,000 calls of a lambda at 1.35 s on wasm), which is the lead, not the library.
  Sizes, class / wasm bytes (`sizes/`): a list counted by a loop 84,402 / 57,341, by
  `stream().filter(..).count()` 94,261 / 61,728; mapped into a list by a loop 145,414 /
  133,052, by `map(..).collect(toList())` 169,378 / 144,046, by `toList()` 176,782 /
  147,040; `sorted().collect(joining(","))` 192,371 / 157,605;
  `collect(groupingBy(String::length, counting()))` 217,664 / 167,895; a sum of squares
  by a loop 47,200 / 32,230, by `IntStream.range(..).map(..).sum()` 91,757 / 61,457.
  A program calling no stream is unchanged in what it holds
  (`JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`), but its
  class table reads 97 files of the library where it read 46 -- `Collection` names
  `Stream`, and a class's member types are read with it: 1.89 -> 1.95 s for a program of
  one list on the interpreter (`.todo/k82`).
- **`String.compareTo(null)` throws** (k12: it answered 1, found by `sorted()` over a
  `null`): `%java-string-compare` and `-ignore-case` test their argument, 23 B of wasm
  in a program comparing strings.
- **Spliterators** (2026-11-20, k38; met by YAVI's `input/Projection`,
  `StreamSupport.stream(iterable.spliterator(), false)`). Every spliterator of the
  library is `rontolisp.internal.Splits`: an iterator's elements, a collection's iterator
  and size taken at the first `tryAdvance` or `estimateSize` (an element added between
  `spliterator()` and that is read, as of a JDK's `ArrayList`). What each collection
  reports is a JDK 25's answer to a probe, pinned by the corpus case
  `a-spliterator-reports-what-a-jdk-reports-of-each-collection-and-a-stream-is-made-of-one`
  (50 collections): its characteristics, and whether its estimate goes down an element
  given (`ArrayList`, `Vector`, `LinkedList`, `ArrayDeque`, the lists of `List.of`,
  `Arrays.asList`, `nCopies`, a sublist of a `RandomAccess` list, the copy-on-write two,
  a collection of one element) or keeps what it took (every set and map view but those,
  a sublist of a `LinkedList`, the blocking queues, the defaults of `Collection`, `Set`
  and `List` for a class of the program). One element nothing changes (`List.of(e)`,
  `Set.of(e)`, `singleton*`, the entries of a `Map.of` of one pair) is
  `ORDERED|DISTINCT|SIZED|IMMUTABLE|SUBSIZED`, with `NONNULL` but for a `null`;
  `Collections.empty*` is `SIZED|SUBSIZED`; an unmodifiable or synchronized view is its
  held collection's (`ImmutableCollections.HELD` .. `ONE`). `RandomAccess` is what
  `List.spliterator()` tells the two kinds of list by, as a JDK's does.
  `Iterable.spliterator()` takes the iterator at once. `StreamSupport.stream` is
  `ObjectFlow.split`: a pull is a `tryAdvance` into a held cell, the supplier form asks
  for its spliterator at the first pull or count, and `count()` of a `SIZED` one is
  `getExactSizeIfKnown()`, no stage run. **Decided: nothing splits** (`trySplit` answers
  `null`, within the specification: a stream here runs in order, and how a JDK's
  `AbstractSpliterator` batches is its own). Left as measured: a change while a
  spliterator is read throws before the next element, where a JDK's `ArrayList` and
  `HashSet` hand it on first; `Collection.stream()` stays `ObjectFlow.over`, so a
  program's collection overriding `spliterator()` and not `stream()` streams its
  iterator; a `SORTED` or `DISTINCT` spliterator does not make `sorted()` / `distinct()`
  a pass-through. A program of one `ArrayList` reads 94 library files where it read 91
  (`RandomAccess`, `Spliterator`, `Splits`), runs on the interpreter in the same time
  (1.09 to 1.24 s against 1.10 to 1.18 s, five runs each) and lowers to the same 57,206
  B of wasm; YAVI's classic example grew from 655,275 to 655,678 B.
- **The spliterators of numbers and a stream's own** (k26, 2026-11-20). `Spliterator.OfInt`
  / `OfLong` / `OfDouble` are `IntFlow.Walk` and its kin over `Flow.Walked`: the
  elements a pull gives, a size that is a count taken when first asked for, counted
  down an element or kept. `Spliterators`' of an array count down and add `SIZED |
  SUBSIZED`; of an iterator keep the size given, exact but under `CONCURRENT`, and
  hand on every element past it; `Arrays.spliterator` is `ORDERED | IMMUTABLE` over
  that; `StreamSupport.intStream` and its kin are `IntFlow.split`. All a JDK 25's
  (`Prim.java`).
  **Decided: a stream's `spliterator()` reports `ORDERED`, and `SIZED | SUBSIZED` where
  the stream knows its count (`Flow.marks`), and nothing a JDK keeps track of through
  the stages.** What a JDK reports was measured (`Split.java`, 29 sources by 19 stages)
  and is regular: a stream with no stage hands out its source's own spliterator; past
  a stage five marks are left -- `map`, `flatMap`, `mapMulti`, `gather` and a number
  stream's `distinct()` clear `DISTINCT | SORTED`, `sorted()` sets `SORTED | ORDERED`
  (a comparator: `ORDERED`, not `SORTED`), `distinct()` sets `DISTINCT`, `unordered()`
  clears `ORDERED | SORTED` and is no stage of a source in no order, `SIZED` is the
  count, `SUBSIZED` the source's; `sorted()` over `SORTED` takes nothing ahead. It was
  written (the source's marks and a mask in one `int` of the pipeline) and printed 558
  of the 580 lines, and was taken out again for what it cost programs that never ask:
  0.4 to 2.5 KB of wasm in every stream program (`SzMapList` 144,046 -> 145,347 B,
  `SzSorted` 157,605 -> 160,061; the table and where the bytes went:
  `.todo/artefacts/k26-java-front-end-what-the-streams-slice-left/README.md`) -- an
  `&=` of an `int` field is 221 B of a method here, and every stage that changes the
  elements would call it. As committed those programs move by -60 to +337 B. So a
  stream's spliterator differs from a JDK's in the marks named in `deviations.md`, in
  its estimate where the count is not known (`Long.MAX_VALUE`; a JDK's is its
  source's), in being no source's own (`StreamSupport.stream(s, false).spliterator()
  != s`), and `sorted()` always takes every element. Lead (`.todo/k82`): marks kept
  only in a program that asks for one, which needs a member of the library lowered by
  what the program reaches, as the third function of a format is.
- **The streams of a `Random`** (k26; `IntFlow.drawn` and its kin: so many elements of
  a supplier, each drawn when the stream is read, as many as a `long` counts where no
  size is given, so `count()` runs nothing). An element between two bounds: the
  documentation gives a long's draws -- one `nextLong`, masked where the width is a
  power of two, else its upper 63 bits reduced by the width and drawn again while
  they fall in what the width does not divide, a width no `long` holds by drawing
  until one is within -- and a double's (`nextDouble` scaled, the double below the
  bound where rounding reaches it). For an int no documentation says which; of
  `nextInt(width) + origin` and the long's draws over `nextInt`, the second gave a
  JDK 25's numbers on all 2,891 seeded ranges, the first on 2,414 (`Rand.java`).
  **`nextInt`, `nextLong`, `nextDouble` and `nextFloat` of bounds** (k47) are those
  draws (a JDK's numbers on every seeded range of k47's `Rand.java` and `Wide.java`);
  bounds too far apart for a `double` or a `float`, which `nextDouble(origin, bound)`
  takes and a stream refuses, are halved and the draw doubled (14,000 doubles and
  10,000 floats a JDK's, where scaling by each bound gave 7,417 doubles).
  `nextBytes` and `nextFloat()` are the documentation's: a `nextInt()` each four bytes,
  lowest first; 24 bits over 2^24.
- **The collectors of a concurrent map** (k26) are `groupingBy` / `toMap` over a
  `ConcurrentHashMap` with two more marks (`Collectors.grouped`, `once`, `merged`):
  `CONCURRENT`, `UNORDERED`, and `IDENTITY_FINISH` where the downstream has it. A JDK
  checks no argument of them for `null` when the collector is made (`Conmap.java`).
- **Not there** (`.todo/k82`): a pipeline run in halves (`parallel()` is a flag), the
  marks of a stream's spliterator, a collector's `finisher` skipped under
  `IDENTITY_FINISH`.

## java.net.http (2026-11-18, k03)

**Invariant: a request is the lowered program's own `rontolisp:fetch` call, so every
transport fetch has carries the client and none is new; the API is the JDK's, and the
host's future is never a value of the program.** No class of a package of our own was
needed: what a transport cannot give has no member (refused by name as any missing
member) or is refused where the request is sent (`deviations.md`, "The HTTP client").

- **Where it sits.** `java.net.http.HttpClient` / `HttpRequest` (abstract classes),
  `HttpResponse` (an interface), `HttpHeaders`, `java.net.URI`, `URISyntaxException`,
  `SocketException`, `ConnectException` are Java source; the one client, its builders,
  the request, the response, the publisher (`Body`) and the subscriber (`Reader`) are
  nested classes of `rontolisp.internal.Http`, whose seven `native` members are the
  transport (`JavaIntrinsics.NATIVES`). `java.net` joined `LIBRARY_PACKAGES` (it is
  `java.base`'s); `import module java.net.http` brings the one package of that module.
- **`start0(url, method, fields, body)` is lowered to
  `(handler-case (vector (rontolisp:fetch url (list :method m :headers (%java-http-fields fs) :body b))) (error (c) (princ-to-string c)))`**
  in the library method that calls it, so the forms the seam hands on NAME fetch:
  `HostFetchLibrary.processForRunner` and `HttpLibrary` splice their transports for it,
  and a plain Preview 1 `.wasm` is refused by `WasmFetchCompiler.reject` (whose words
  name the client since k03). A program reaching no member of the client holds no fetch
  (`lowerReached`). `reply0` is `%java-http-reply` (`java.lisp`, "java.net.http"): it
  forces the future, drains `:body` chunk by chunk (a `--component` read of a chunk in
  flight settles to a future, forced in turn, as `%clojure-io-next-chunk`), joins with
  the prelude's `%octets-join`, and answers `#(status fields octets)` -- the fields a
  flat vector of names and values, fetch's sorted lowercase list -- or the TEXT of what
  failed (the call's refusal, the transport, a transfer cut short). The source throws
  from the text (`Http.failure`), as `Pattern` throws the engine's refusal: `RAISED`
  did not grow.
- **The future travels in a vector (measured).** Handed back bare, it was awaited: a
  resumable function answers through an `await`, which flattens a future it is handed.
  (Since k07 the lowered form is `(%java-http-exchange (rontolisp:fetch ..))`, the vector
  made by the run time.) `started()` became resumable in a program with a `BodyPublisher` and a `sendAsync`
  (`Body.octets()` calls a `Supplier` field, which reaches the resumable supplier
  `sendAsync` writes), its caller then waited for the host's future, and the scheduler
  -- which settles only its own -- ended the program with `every thread .. waits`. On
  the component only: `--native` blocks in the thunk. Pinned by the corpus's `subset`
  program on the component leg.
- **`send` waits in `reply0`; `sendAsync` sends at the call and hands the rest to
  `CompletableFuture.supplyAsync`** (the client's `executor`, else the common pool: a
  JDK 25 ran a dependent on `ForkJoinPool.commonPool-worker-1`, measured), so a program
  calling it starts threads and a wasm target lowers it resumable. Several requests are
  in flight at once on every transport (the corpus's `/held` + `/release` case: the
  reply to the first ends once the second arrived).
- **The wait for a reply is a suspension** (`JavaResumable`: `%java-http-reply` ->
  `%java-suspending-http-reply`, "general" as a sleep is). Under a component the reply is
  read as the host delivers it and the thread waits among the others ("The host's events
  give the turns under a component", k07). In a `--native` module it still gives one turn
  and then waits for the runner with no thread going on -- a thread asleep included, and a
  request of the program's own server: a `--native` fetch's future is deferred (it
  settles in the await; the runner has `rlhttp.head` alone, which blocks), so the
  scheduler cannot wait for the soonest of its timed threads, its replies and its
  requests together. What that takes of the runner is `.todo/k18`. Yielding while others
  can go on was rejected: a thread spinning on a flag the reply's dependent sets never
  lets the reply in.
- **What the backend took: a deferred future where the state machines run.** A Java
  program that starts threads names `%future-new`, which turns `asyncMode` on in a
  Preview 1 module; the runner's fetch answers `%future-deferred`, which that mode
  refused by name ("belongs to the degenerate tier"): threads and HTTP did not compile
  for `--native`. Now a `TYPE_FUTURE` in `STATE_DEFERRED` (4) holds the thunk and
  `_future_poll` calls it through the arity-0 dispatch and resolves its answer
  ([async-await.md](async-await.md), "Preview-1 wasm-GC, a module naming ..").
- **Redirects**: the policy is Java (`Client.redirected`: `NORMAL` and `ALWAYS`, the
  statuses, four hops, a 303 and a POST's 301/302 as a GET, the credentials left behind
  off the origin -- what j95 measured for the Clojure client, asked again of JDK 25 for
  `previousResponse()`, the final `request()` and a PUT's 302, which keeps its method
  and body); the target is `URI.resolve`, which is `%uri-resolve`.
- **`java.net.URI` is cut and checked by `eval/uri.lisp`** (`eval/UriLibrary`, gate
  `%URI-`, spliced right outside the front ends' libraries beside `RegexLibrary`): the
  Clojure client's `split` / `remove-dots` / `resolve` moved there unchanged but for a
  base with no scheme or authority (`a/b` + `c` is `a/c`), and its scanner as
  `%uri-refusal`, answering `(text . index)` where it raised; `%clojure-http-url` keeps
  its own type and scheme refusals over it. Added from a JDK 25's answers (a grid of 76
  references and 18 refusals): an opaque part (`Illegal character in opaque part`,
  `Expected scheme-specific part`), an address in brackets (closing bracket, its
  characters; not its syntax). What an authority's host is -- an IPv4 address of four
  numbers, or labels the last starting with a letter; else no host, no user, no port --
  is `URI.server` / `named` in Java, measured on the same grid.
- **Measured against JDK 25** (the probes are the corpus's cases): the builders'
  refusals and their order (name, value, token, restricted, value's characters; the
  characters a name, a method and a value take, each of 0..0x17F asked), the trimmed
  values, `HttpHeaders`' and `HttpRequest`'s `hashCode` formulas and `toString`s, a
  blocking `send`'s exception holding the failure as its cause.
- **A transport's failure is an `IOException` with its text**, a refused connection a
  `ConnectException` by the three texts the transports give (`java.net.ConnectException`,
  `:CONNECTION-REFUSED`, `cannot connect`).
- **`BodyHandlers.ofString()` reads three charsets off `Content-Type`** (UTF-8,
  ISO-8859-1, US-ASCII) and UTF-8 otherwise: a lookup by a computed name would put the
  UTF-16 codec into every program reading a text ("java.io", j98's measurement).
- **Sizes** (2026-11-18, wasmtime 49.0.0; class / component bytes): one blocking GET
  printed 470,670 / 476,954; the corpus's `client` program 580,543 / 559,890; its
  `async` program (threads, resumable) 623,011 / 673,302. `Hello` and a counter under
  four threads are byte-identical to before on both wasm targets.
- **Pinned by** `FetchSpecE2eTest#javaHttpClient` (`java-http-spec.yaml`: three
  programs, each under the JDK running the test -- the oracle -- and on the interpreter,
  the JVM, `--native` and the component; `subset`, what the client refuses by name, has
  no JDK leg), `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`
  (the fetch form, the suspension), `#theLibrarysSourceIsJavaAJdkCompiles` (with
  `--limit-modules java.base`, so the package of another module is one of the patch),
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (the Preview
  1 refusal), `NativeFetchTest` (the deferred future beside `%future-new`), the
  `doc/*/java/index.md` example (`DocExamplesTest` points its URLs at its local origin).
- **Time limits (k05, 2026-11-19)**: `HttpRequest.Builder.timeout` and
  `HttpClient.Builder.connectTimeout` are fetch's `:first-byte-timeout` and
  `:connect-timeout` ([fetch-http.md](fetch-http.md), "Timeouts"), in milliseconds (a
  part of one is one, what passes an int is an int). **Only a program that gives a
  builder a time limit hands fetch the two options**: `Http.limit` makes the `TimedWire`
  the requests leave by from then on (`timed0`, lowered beside `start0` to the same
  fetch form with the two keywords), and the plain `Wire` of every other program calls
  `start0` -- a class a program never makes is in no dispatcher (`lowerReached`), so
  such a program names no timeout keyword and its transports carry no timeout arm. The
  timed wire also owns what a timeout is to a program: `HttpTimeoutException` /
  `HttpConnectTimeoutException` by the texts the four transports give (`request timed
  out`, `connect timed out`, `CONNECTION-READ-TIMEOUT`, `CONNECTION-TIMEOUT`,
  `HTTP-RESPONSE-TIMEOUT`), the cause chain a blocking `send` adds. Measured against JDK
  25 (the corpus's case and a probe against a blackholed address, identical on four
  legs): `Invalid duration: PT0S`, a request timeout of 100 ms against a head held a
  second (`request timed out`, no cause), a timeout that passes while connecting is a
  connect timeout, a redirect keeps its request's timeout. Sizes (component): a blocking
  GET with no time limit 470,546 -> 472,868 B (the wire's class, a slot in the request
  and in the client), with one 489,891 B. A program's own `HttpRequest` class stating a
  `timeout()` is refused by name where no builder was given one. A component that
  serves cannot time a request (an `IOException` with fetch's refusal: `.todo/148`).
- **Not made** (`.todo/k82`): bodies as they arrive (`ofInputStream` reads a body
  already in, `ofLines`, a program's own `BodySubscriber`), the `--host-fetch` reactor
  as a leg, `URI`'s constructors of several parts.

## com.sun.net.httpserver (2026-11-19, k04; the threads, the executor, filters and authenticators: k11)

**Invariant: a server is the lowered program's own `rontolisp::%http-serve-begin` call, so
every transport a served application has carries it and none is new; the API is the JDK's;
an exchange answers through Clack's delayed response, whole or through its streaming
writer, which the transport that can sends chunk by chunk; a request is a task the
server's executor runs, and on one stack a thread of the program's own scheduler.** No
class of a package of our own was needed (`deviations.md`, "The HTTP server").

- **Where it sits.** `com.sun.net.httpserver.HttpServer` / `HttpContext` / `HttpExchange`
  (abstract classes), `HttpHandler`, `Headers`, `java.net.InetSocketAddress`,
  `InetAddress`, `SocketAddress`, `BindException` are Java source; the server, its
  contexts, the exchange and its two streams are nested classes of
  `rontolisp.internal.HttpServing`, whose `native` members are the transport
  (`JavaIntrinsics.NATIVES`). `com.sun.net.httpserver.` is a platform prefix beside
  `java.` / `javax.` (`JavaClasses.isPlatform`: a class of it the library lacks is refused
  by name, no file of the program is looked for); `import module jdk.httpserver` brings
  the package.
- **`open0(server, port, host)` is lowered to
  `(handler-case (%http-serve-begin (lambda (env) (lambda (responder) (%java-http-request (lambda (handed) (HttpServing.answer server env handed)) responder))) port host) (error (c) (princ-to-string c)))`**
  where it is called, so the forms the seam hands on NAME the transport:
  `HttpServeLibrary` (first pass of `expand`) splices the target's leg, and under
  `--component` the directive inside it makes the output a `wasi:http/handler` component
  (`.kb/clack.md`, "Transport selection"). A program reaching no member of the server
  holds none of it. A plain Preview 1 module gets the directive's call-time error, which
  `create` throws as an `IOException` in the server's own words (`HttpServing.failure`
  reads `requires --component` off the text: `an HttpServer needs a transport: compile
  with --component .. or with --native`).
- **The two-step serve (`http-serve.lisp`).** `%http-serve` either blocks or cannot be
  joined later, and a Java program goes on after `create` and ends when its server
  stopped. So: `%http-serve-begin app port address` binds and answers the server at once
  on every leg (the socket seam's handle; on `--native` `#(listener app stopped)` after
  the runner's `serve`, which accepts and queues from then on; `t` where the host owns
  the port), `%http-serve-run server` answers once it stopped (the join on the socket
  legs; **the serving loop itself on `--native`**, until `%http-serve-stop` set the flag;
  at once under a host), `%http-serve-port` the bound port or nil. `%http-serve` is
  unchanged: Ring's `:join? false` on `--native` still never returns.
- **When `main` runs, and the program's end, where the host has threads.** `bind` (so
  `create`) binds; `start` opens a gate; **the lowering appends `HttpServing.serve()`
  after `main`** in a program reaching the server (`JavaLowering.serves`, not on one
  stack): `run0` is `(progn (%java-http-wait) (%http-serve-run handle))`. The thread the
  program started on parks (a ticket on its carrier, taken by the last `stop`; a park so
  that the interpreter's `System.exit` on a request thread wakes it, "Threads"), then
  joins the transport.
- **On one stack a request is a thread of the scheduler's, and the scheduler serves
  (k11; a `--native` module still, a component no longer: there the request's task is
  suspended and the host's events give the turns, k07, "The host's events give the turns
  under a component").** Every program reaching the server is lowered resumable on wasm
  (`JavaLowering.scheduled`: it starts threads or serves), its entry is
  `%java-thread-main` and nothing follows `main`. `%java-http-turn gate server state`
  sets `%java-serving`, a function of the soonest time a thread waits for, while the
  server is started; `%java-thread-finish` runs while a thread that is no daemon lives OR
  a server runs, and where no thread can go on it calls it: `%http-serve-step server
  limit` (`http-serve.lisp`) serves one request of a `--native` module's, waiting `limit`
  milliseconds at most for it (the runner's `awaitRequest`, then `nextRequest`), and
  answers `:host` on a component, where the finish returns -- **the instance's start
  ends where `main` returned or every thread waits**, and the host calls `handle`. The
  transport's call of the application is `%java-http-request thunk`: a carrier, the
  `async-defun` `%java-http-thread` queued as a started thread is, and
  `%java-thread-run`, the scheduler's loop until that thread ended (a `--native` module
  takes the next request there too, nested, when no thread can go on). `JavaResumable`
  reads the thunk as awaited (`HTTP_REQUEST`, beside `THREAD_START`), so it is no
  `%java-now`. So a `main` waiting for ever after `start()`, a handler waiting for a
  thread, `Thread.currentThread()` of a handler being no thread of `main`'s all hold on
  both wasm targets (the corpus's `/worker`, `/thread` and its `main`, which awaits a
  latch the stop counts down).
- **A thread outlives the request that started it: `%task-swap` (k11; under a component
  the swap is the pump's since k07, and what the host still owes a thread holds the task
  it was asked under).** Measured first:
  a pool's thread started while request 1 was answered was never resumed while request 3
  was (`every thread .. waits`, the third request of a component). A frame is owned by
  the task current when it is made, and `_wake_list` defers a waiter owned by another
  task to that task's doorbell -- a task long ended. `%java-http-request` therefore runs
  with no current task (`rontolisp::%task-swap nil`, restored after;
  [async-await.md](async-await.md), "`%task-swap`"): the frames of the program's threads
  belong to none and are resumed on the stack of whichever request runs the scheduler,
  which is also the task whose `task.return` and `stream.write` they then call.
- **Measured, the instance and requests at once, BEFORE k07** (2026-11-19, wasmtime 49.0.0; what
  a waiting handler does to its instance now is in "The host's events give the turns under
  a component"): six
  requests at once to a handler sleeping 500 ms went to six instances (each answered
  `entered 1`), a seventh after them to one of those (`entered 2`); a request sent 0.5 s
  after one whose handler sleeps 2 s was answered after 1.5 s -- the host handed it to
  the sleeping instance, which was not entered until its request was answered. So an
  instance is never entered while a call of it blocks: nothing of the run time's (the
  carrier variable) has to be kept across a blocking call, a waiting handler holds
  back what the host queued behind it, and one waiting for what another request brings
  never gets it -- `%java-stuck`, or for ever while a timed thread of the program keeps
  waking (a scheduled pool ticking: every request to that instance then waits too).
- **Measured, `wasmtime serve` 49.0.0**: an instance answers up to 128 requests, 16 at
  once, and is dropped after 1 s idle (`--max-instance-reuse-count`,
  `--max-instance-concurrent-reuse-count`, `--idle-instance-timeout`); a counter in a
  static field went 1, 2, 3, then 1 again after 2 s, and 16 requests at once went to
  several instances, each counting its own. The program's standard output is the host's log, a line `stdout [n] :: ..`.
  `wasi:http` hands no `Host` field and no peer: the local port is 80, the peer nil
  (`getRemoteAddress()` is `0.0.0.0:0`).
- **A request before `start()` waits** (the gate, `%java-http-admitted`: a condition
  variable on the host, a flag on one stack), as a connection a JDK has not accepted
  yet; binding at `start` was rejected: `create` would not throw `BindException`, and
  `getAddress()` of port 0 would be 0 until the start.
- **A request is a task, and the thread the transport called on waits for it (k11).**
  `HttpServing.answer` -> `Server.dispatch`: with no executor `Task.work()` runs under
  the server's `Turn` monitor (a JDK's `HTTP-Dispatcher` thread runs the handlers of
  such a server one at a time), else `executor.execute(task)` under that monitor (one
  thread of a JDK's hands every request over) and `Task.await()`. Why each is as it is:
  the lock is of a class of its own because `JavaResumable.Lock.mayBe` takes two
  `new Object()` locks for one, which would make every `synchronized (LOCK)` of a
  program an entry that may block; with no executor the task is called by its class,
  since through `Runnable.run`'s dispatcher the call reaches every `Runnable` a program
  makes. Waiting makes "the exchange ends with its handler" hold on every transport.
  The host variant binds `%java-carrier` to a carrier of its own per request -- without
  it every request thread found the global carrier and WAS the thread the program
  started on (a `wait`, an interrupt, the monitor entered last) --, `bind` sets
  `%java-shared` / `%java-main` as the first `Thread.start()` does, and reaching the
  server turns `JavaClasses.monitors` on, on every target. The thread a transport calls
  on has no `Thread` until it is asked for: slot 0 of its carrier holds the name it is
  given, `HTTP-Dispatcher` (`%java-thread-named`; an `instanceof` and two casts in
  `currentThread()` were tried first and put `ClassCastException` into every program
  with a thread).
- **`stop(delay)` (k11).** Measured, JDK 25: `stop(3)` with a request in hand that takes
  1 s returns after 1 s and the request is answered; `stop(0)` cuts it; `stop(2)` by a
  handler takes 2 s (its own request is in hand) and its response, written before, is
  read. Here the server counts the requests in hand, `stop` waits on its monitor for
  none or the delay, and the transport's server is closed by the thread that calls
  `stop` -- or, called by a handler (a `Task` whose `runner` is the calling carrier),
  by `Server.gave()` once none is in hand: that is the thread the transport called on,
  where `RontoHttpServer.stopServer` knows it is in a handler and lets the responses
  out (a close on an executor's thread would cut the caller's own response).
- **Filters, authenticators, canned handlers (k11)** are Java source
  (`Filter`, `Request`, `HttpHandlers`, `Authenticator`, `BasicAuthenticator`,
  `HttpPrincipal`, `java.security.Principal`). A context given neither runs its handler
  directly: `getFilters()` / `setAuthenticator` make its `HttpServing.Guarded`, so a
  program naming neither lowers none of it. Measured against JDK 25 (the corpus's
  requests): the filters in their order, then the authenticator, then the handler (a
  filter before the handler sees no principal); a chain run a second time runs the
  handler again; `Request.with` keeps a field already there; an adapted request's fields
  are fixed; `BasicAuthenticator` asks again (`Retry`, `WWW-Authenticate: Basic
  realm="..", charset="UTF-8"`, the charset only for UTF-8) with no `Authorization` and
  after refused credentials (`Failure` with the field), refuses another scheme with no
  field, takes the scheme in any case, the first colon as the end of the user, padding
  left out; a realm is refused for a quote, a control, 0x7F or a character past 0xFF
  with no backslash before it, and for a backslash at its end. The base64 decoder is
  RFC 4648's, private to the class.
- **An exchange answers through Clack's delayed response.** `sendResponseHeaders(code, n)`:
  `-1`, a 1xx/204/304 and a HEAD respond at once with no body; `n > 0` keeps the body
  and responds when the n-th octet is written; `0` hands the responder the head alone
  and writes each chunk through the writer it answers (`%java-http-stream` / `-write` /
  `-finish`). At the handler's return the exchange is closed, and one that answered
  nothing is a 500. The refusals' words are a JDK 25's (`headers already sent`,
  `response headers not sent yet`, `too many bytes to write to stream`,
  `insufficient bytes written to stream`, `stream closed`, `Stream is closed`); a context
  is the longest path the DECODED request path starts with; attributes are the
  context's (a JDK 25 answered `was one` on the second request); a field's name is kept
  first letter upper, rest lower, and a value's CR / LF checks give the four messages
  the probe printed.
- **What the transports took** (`.kb/http-server.md`): Clack's streaming writer in the
  one normalizer (kept and joined where a transport sends a response whole; before, it
  was refused), the component's `%serve-respond` (the head through `task.return` from
  inside the handler, each chunk a blocking `stream.write`), and
  `RontoHttpServer.stopServer` called by a handler stopping on a thread of its own once
  the requests in hand are answered (before, `server.stop(0)` in a handler closed the
  connection under its own response).
- **Measured against JDK 25** (the probes are the corpus's requests and log): everything
  above, and what is NOT matched, stated in `deviations.md`: a handler that throws, a
  body cut short and a handler answering nothing are a 500 (a JDK closes the connection
  for the first and leaves the other two unanswered for ever: the JDK leg waits 3 s,
  `unanswered:`); a write after a chunked close is `IOException: stream closed` (a
  JDK's own `StreamClosedException`). Two JDK facts not to copy: a stream taken BEFORE
  the head and written after it sends nothing, and a refused `sendResponseHeaders(200, -2)`
  breaks the chunked send after it (the reply's head, then nothing).
- **A handler that sends a request through the client** leaves its instance to the host
  under `wasmtime serve` (k07). In a `--native` module it waits in the runner for the
  reply ("java.net.http"): the program's other threads take one turn first and none goes
  on while it waits, so a program asking its own server from `main` still needs the
  host's threads (`.todo/k18`).
- **Sizes** (2026-11-19, wasmtime 49.0.0; class / component bytes): a server of one
  context answering `hello` 300,455 / 379,750; `examples/java/http/Server.java` (four
  contexts, a query split, a chunked body) 480,883 as a component;
  `examples/java/httpbin/Httpbin.java` 618,617 (k13). **With a request a thread (k11,
  the same day, measured on the tree before and after)**: the `hello` server 291,832 ->
  317,405 / 374,807 -> 432,210 (+15%; its `--native` executable 7,199,696 -> 8,046,832),
  `http/Server.java` 457,988 -> 514,871, `Httpbin.java` 618,617 -> 684,081. Of the
  component's 57 KB (`-Drontolisp.wasm.debug-func-sizes`): 17 KB are eight more resume
  bodies (the thread the program starts on, the request's, the waits), 9 KB the monitors
  (`%java-monitor-enter` / `-exit` / `-left` / `-notify`), 4 KB the scheduler, the rest
  `Thread`, the task and the server's counts. Every served program pays it: the
  hand-over to an executor is what reaches `wait`. `.todo/k15` has the alternative not
  measured (a plain call of the handler in a program none of whose threads can wait).
- **Pinned by** `FetchSpecE2eTest#javaHttpServer` (`java-http-server-spec.yaml`: the
  program started as a server under the JDK running the test and on the interpreter,
  the JVM, `--native` and `wasmtime serve`, 39 requests in turn, the log, the exit once
  stopped -- since k11 every leg binds port 0 and tells its port, `main` awaits a latch
  the stop counts down, the executor is a pool of four named threads (`/thread`,
  `/counted`'s bound), `/worker` waits for a thread that answers, and the filters, the
  canned handlers, the authenticators and the address forms are asked beside a JDK; `/stream` asks the test's origin whether its first chunk was read before it
  writes the second -- `seen` on the JDK and the component, `unseen` where the body goes
  whole; its program `httpbin` is `examples/java/httpbin/Httpbin.java` read as it stands
  (`example:`), bound to the `PORT` the harness sets, never stopped (`endless:`), with
  the `Host` and `User-agent` lines of a body left out (`unshown:`): every leg answers the
  JDK's seven bodies byte for byte, k13), `HttpHandlerTest#stoppableSeamServesJoinsAndStops` (the stop by a handler),
  `#directiveSupportsTheDelayedResponse` (the writer on the interpreter's mirror),
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the begin
  form, the wait after `main` on the host, and on one stack the scheduler's entry, the
  resumable `answer` and the monitor held),
  `#theLibrarysSourceIsJavaAJdkCompiles`, the `doc/*/java/index.md` example,
  `examples/java/http/` (`ExamplesE2eTest`).
- **`InetAddress.getByName` reads numbers and `localhost` (k11)**, as a JDK 25 answered
  a probe: one to four decimal numbers of any length, each but the last an octet and the
  last filling the octets left (`1.2.3` is `1.2.0.3`, `65536` is `0.1.0.0`), `null` and
  `""` the loopback address. Any other name is an `UnknownHostException`: no target has
  a name service the four share.
- **A front end bug found by it**: a static method of an interface was found through a
  class implementing it (`Headers.of("Allow", "GET")` resolved to `Map.of`, which fits
  with no variable arity). `JavaLowering.methods` leaves it to its interface;
  `java-spec.yaml` `an-interface-has-defaults-static-and-private-methods-and-constants`.
- **Not made** (`.todo/k15`): a body sent as it is written on the interpreter, the JVM
  and `--native`; the values of a field sent twice; a second server; `HttpsServer`
  (`.todo/050`), `SimpleFileServer`; name lookup; a JDK's thread going on to the next
  request while an executor's thread answers one.

## Numbers (2026-11-17, j72)

- **`long` is `(%mask-signed-field 64 x)`** (`JavaForms.wrap`), the primitive the Clojure
  front end's unchecked verbs use (`.kb/clojure-frontend.md`): both compilers fuse it with the
  operation under it into one wrapping `ladd`/`lmul`, `i64.add`/`i64.mul`
  (`.kb/jvm-int-fusion.md`, `.kb/wasm-int-fusion.md`), where the mask of an `int` at 64 bits
  needs the bignum literal `2^63`, which declines fusion: every step built a bignum. `x >>> k`
  with a literal `k` is `(logand (ash x -k) (2^(w-k) - 1))`, no bignum either.
- **What the types prove reaches the backends as declarations** (2026-11-20, k56). A
  parameter, a local, a `for` and an enhanced `for` variable and a lambda's parameter of an
  integral type are declared of its Common Lisp type at the head of the body binding it
  (`JavaForms.integerType`: `(signed-byte 32)` an `int`, `64` a `long`, `16` and `8` a
  `short` and a `byte`, `(integer 0 1114111)` a `char`, a string's elements being code
  points (`doc/*/java/deviations.md`; `(unsigned-byte 16)` trapped on the first one past
  U+FFFF, `Properties.escaped` of the corpus); `declaring`, and `boundInTurn`, a
  `let` each for several declarators, since a `let*` gives its inner `let` every
  declaration). An operand of an integer operation whose form does not say its range -- a
  call, a field, a conditional -- is asserted with `the` (`JavaForms.known`), a static field
  where it is read (a global no declaration covers), an `int` quotient as `(integer -2^31
  2^31)`: `MIN_VALUE / -1` is the one value past the type, which the wrap takes back. On
  wasm-GC that is a site with no fallback and a variable kept raw where it is read mostly raw
  (`.kb/declarations-type-checks.md`, "Declared integer ranges"); on the JVM a fused leaf cast
  with no fallback where nothing else bails (k65: `Zone.class` 271,493 -> 260,065 B, times
  unchanged); the interpreter reads none. `JavaResumable` drops a `the` around an await.
  A Common Lisp caller handing a method a value out of a parameter's range is a false
  declaration (on wasm a trap, on the JVM an error for a non-integer alone; `doc/*/java/index.md`); a `native` method's integral result is
  wrapped to its type.
- **Every integral wrap is `(%mask-signed-field w x)`** (`JavaForms.wrap`, `signed`). Until
  k56 `int` kept the unconditional mask `(- (logand (+ x 2^31) 2^32-1) 2^31)`: the masked
  field measured the same time (`WrapInt` x10: 0.85 / 0.77 s against 0.91 / 0.74) and
  `Sieve.wasm` 10,380 -> 10,988 bytes, its fallback being `logxor`'s. With the fallbacks gone
  the field is two shifts where the mask is three operations over five-byte constants, and on
  the interpreter one call where the mask is three (2,000,000 steps of `h = 31 * h + i % 7`:
  2.5 -> 2.0-2.2 s); over a lone leaf both compilers fuse it now, its lowering being inline.
  A mask in a `let` or behind a test loses 4x (the j68 artefact README).
- **Measured** (2026-11-20, base `e9313f180`; bytes of wasm unless named): a method of ten
  `x = x + b;` over `int` parameters 2,098 -> 298, over `long` 1,948 -> 214; the zone reader
  (`ZoneChanges.region`, `find`, `rule`, `written`, `number`) 17,920 -> 13,217;
  `ZonedDateTime.of(.., ZoneId.of("Europe/Paris")).plusHours(1)` 197,022 -> 173,608
  (`Zone.class` 278,339 -> 271,945); `Sieve` 22,493 -> 21,305; YAVI `Booking.java`
  1,204,473 -> 1,129,086, `Member.java` 663,174 -> 647,893; the walk of the regions 599,559
  -> 534,113 (`Regions.jar` 221,955 -> 217,368). The choices, by the same programs: every
  declared local raw against none, Booking 1,136,369 against 1,143,837, by raw use
  1,133,686; every declared parameter raw 1,143,772 (Zone 178,508), by raw use 1,133,686
  (173,697); the mask against the field, Booking 1,133,686 / 1,129,086, Zone 173,697 /
  173,608, `ten` 448 / 298. Times (wasm / JVM, best of three, CPU seconds unless named):
  the walk 31 / 16.5 s of wall time before and after, its lines' strings, and on the
  interpreter 6.9 -> 6.4 min (the masked field one call); 50,000,000 steps of the sieve loop
  9.0 s before and after; 200 walks of `Sieve`'s tree 4.2 -> 4.0 / 1.8 s; 30,000,000 `int`
  and 10,000,000 `long` steps 0.164 -> 0.110 / 0.50 s.
- **An array the types prove is declared of its vector or nil** (2026-11-20, k64): a
  parameter, a local, a loop and a lambda's variable of an array type is declared `(or null
  (simple-array E (*)))` (`JavaForms.arrayType`, `declaredType`; E the element type a read
  answers: `(unsigned-byte 32)` an `int[]` as its vector holds it, `(signed-byte 64)` a
  `long[]`, `bit` a `boolean[]`, `t` an array of references), a static field's read is
  asserted with `the` of the same, and an element access through any other form -- an instance
  field, a call, a row of an array of arrays -- too (`knownArray`); `.length` and the enhanced
  `for` take `(the (simple-array E (*)) (or a (%java-npe)))` (`vector`), no nil. A null array is
  read as before: the access evaluates its index (and a store its value) and the backends'
  check raises what `%java-exception-of` makes `NullPointerException` (wasm keeps that order:
  `.kb/declarations-type-checks.md`, "Declaration-driven array emission"). The interpreter
  and the JVM read no array declaration; the wasm kinds and a general array's integer elements
  do (`.kb/wasm-int-fusion.md`, "Ranges, and sites with nothing to bail on"). A Common Lisp vector of
  another representation handed to a parameter is a false declaration (`doc/*/java/index.md`).
- **Measured** (k64, base `303deba92`; bytes of wasm, CPU seconds best of 2-4 on a loaded
  64-core box; the programs and the script in
  `.todo/artefacts/k64-java-wasm-an-array-the-types-prove-takes-the-generic-dispatch/`): the zone reader 13,217 -> 12,765 (`region` 7,012 -> 6,975, `find` 3,046 ->
  2,779, `rule` 2,355 -> 2,208), its instance methods `placed` 5,166 -> 2,793 and `ruled` 4,829
  -> 3,028; `ZonedDateTime.of(.., ZoneId.of("Europe/Paris")).plusHours(1)` 173,105 -> 166,183;
  `Sieve` 21,305 -> 19,699; `SieveLoop` 18,402 -> 16,797, its time 8.2-9.0 -> 7.6-8.3 s (a
  sieve of 50,000,000 -- not bound by memory: the store's boxed index, k76 below); YAVI `Booking.java` 1,128,428 -> 1,125,245,
  `Member.java` 648,210 -> 646,545; 2,000,000 offsets of an instant and a local date-time in
  Europe/Paris 4.83 -> 2.59 s; a `long[1000]` summed 10^5 times 93.8 -> 1.44 s
  (`.kb/wasm-int-fusion.md`). The JVM (that offsets program's jar 65,079 -> 65,003 B, both
  times) and the interpreter are unchanged. **The premise was the dispatch's bytes, and it held only where nothing had pinned
  the array**: the type-test fold had already cut each undeclared site of the reader to the
  general arm, `region`'s arrays were general by their `make-array` inits, and a general
  array's `length` at the speed levels is a 57 B arm where the call is 3 (nine in `region`).
  What is left of the reader: five `make-array`s of a computed size (some 150 B each: the dims
  of any rank, `_vec_sync`), three generic divisions of declared integers (an `i64.div_s` since
  k68, below) and the checks of what the inline arms refuse.
- **A quotient is an `i64.div_s` on wasm** (2026-11-20, k68): `a / b` and `Math.floorDiv` of
  declared or asserted operands (`floorDiv` asserts its operands as an operator does) are an
  operation of the fused tree, a zero divisor reported as before and `MIN_VALUE / -1` wrapped by
  the mask (`.kb/wasm-int-fusion.md`, "Quotients of two integers", where the numbers are: the
  zone reader 12,765 -> 10,951 B, `Zone` 166,183 -> 157,926, 30,000,000 divisions 1.80 -> 0.67
  s). The JVM's `_iquo` was already a `LDIV`.
- **`new T[n]` is a rank-1 `make-array` on wasm** (2026-11-21, k76): the size is asserted
  `(the (integer 0 2147483647) (%java-array-length n))`, which the dimension parse reads as an
  integer (`.kb/array-literals.md`, "What a wasm `make-array` site costs"); and a store into an
  array of an `int`, `char`, `short` or `byte` takes a raw index (`.kb/declarations-type-checks.md`,
  "Declaration-driven array emission"). Measured (base `57598eafd`, bytes of wasm; the
  programs and `measure.sh` of k64's artefact): the zone reader 10,483 -> 10,289 (`region`
  6,085 -> 5,939); `Zone` 155,833 -> 155,201; `Sieve` 19,699 -> 19,253; `SieveLoop` 16,797 ->
  16,402 and 8.9 -> 1.8 s (the JVM 2.9 s); `ZoneLoop` 104,378 -> 103,839, `LongSum` 17,358 ->
  17,237, both times unchanged; YAVI `Booking.java` 1,106,560 -> 1,098,196, `Member.java`
  641,617 -> 637,212. No function of these grew.
- **A `long[]` store, an array of references', takes a raw index too; `new T[n][m]` is
  lowered at its site** (2026-11-21, k84). The first is the `GENERAL` arm of the pinned
  store (`.kb/declarations-type-checks.md`, "Declaration-driven array emission"). The second
  was `%java-new-array`: a list of the lengths, a call per row and a `make-array` of the
  dims of any rank; now the lengths are evaluated, then checked into variables declared
  `(integer 0 2147483647)`, and each level but the last is a `while` over a declared index
  filling a vector (`JavaLowering.newArray`). Measured (base `46d8ae6c2`, wasm unless named,
  CPU seconds best of three on a loaded 64-core box; the programs in
  `.todo/artefacts/k84-java-wasm-a-general-arrays-store-and-new-of-several-sizes/`):
  10^8 stores into a `long[1000]` 0.60 -> 0.45 s (an `int[]`'s 0.42 both), into a
  `String[1000]` 0.58 -> 0.41; 10^6 `new int[4][4]` 0.59 -> 0.29 s (a loop the program
  writes by hand 0.27), the module 27,611 -> 18,313 B (`%java-new-rows` was 1,605 B, the
  site grew 1,280 -> 1,341), the JVM 10^7 of them 4.5-4.8 -> 4.0-4.7 s, the interpreter
  10^5 3.4-4.0 -> 3.2 s. Sizes: the zone reader 10,289 -> 10,008, `Zone` 155,201 ->
  154,862, `ZoneLoop` 103,839 -> 103,501, YAVI `Booking.java` 1,100,061 -> 1,098,914,
  `Member.java` 638,954 -> 638,239 (one function of them grew a byte). `new T[n][m]` is
  rare where measured: none in YAVI 0.18.0, one in the examples (`GlyphGen.java`), none in
  the library's sources.
- **`byte` and `short`** are integers an operator promotes to `int` and an assignment of a
  constant that fits, a cast or a compound assignment narrows (`JavaForms.signed`).
  **`float` is the double of its value** -- every binary32 is a binary64 -- and each operation's
  result is `(%single-float (op a b))`: rounded from the binary64 result, which is the binary32
  result for `+ - * /` (the double holds every bit the rounding reads). A `long` widens to it
  through `%java-long-float`, rounded once. `Float.toString` is `%java-float-string`: of the
  decimals inside the float's rounding interval the shortest (two digits at least), the nearest
  of those, laid out by the nearest double's own text. `Float.parseFloat` reads the digits
  exactly to the nearest binary32 (`%java-nearest-binary`, the double reader's, with the
  precision and the exponent range as arguments).
- **`%` of doubles** (and floats) is `%java-double-rem`: the host's `rem` is the exact fmod on
  four backends (measured), but signals for a zero divisor and gives `0.0` for `-4.0 % 2`.
- **An array of a primitive type has the element type that holds its values**
  (`elementSpecifier`): `int[]` `(unsigned-byte 32)`, `byte[]` `(unsigned-byte 8)`, `short[]`
  and `char[]` `(unsigned-byte 16)`, `float[]` `single-float`, `double[]` `double-float`,
  `boolean[]` `bit`; `long[]` and an array of references `T`. A store masks to the width (all
  four backends), a read takes the low bits back signed (`elementRead`), a `boolean` is a bit.
  So an `int[]` is a packed vector: on the JVM `%aset` of one runs the `_iv` arms instead of the
  general array's, and `SieveLoop` (the sieve half of `Sieve`) went 0.98 -> 0.48 s (wasm 0.37 ->
  0.27). `toCharArray` gives UTF-16 units; `new String(char[])` reads a surrogate pair as its
  character. `Arrays.sort(int[])` is `%java-sort-ints` (signed order over the stored bits).
- **An index outside an array is `ArrayIndexOutOfBoundsException`** with the language's message:
  the backends' error is a `type-error` of `(integer 0 (length))` on four backends, which
  `%lisp-error` reads (`%java-index-error`) in a program that indexes an array; a `catch` of
  `IndexOutOfBoundsException` or of the class itself takes Lisp errors.
- **The virtual call keeps the `cond`.** Measured on `SieveTree` (2^18 leaves, 20 walks), JVM /
  wasm: the `cond` over the structure predicates 0.28 / 0.41 s, a generic function with three
  methods 0.27 / 0.45, a per-class vector of functions in a slot 0.32 / 0.84.
- **`Math.ulp`, `nextUp`, `nextDown`, `fma`, `scalb`** of a double and of a float are
  `%java-ulp` / `-next` / `-fma` / `-scalb`, WIDE `t` for a double: `ulp` and the neighbours
  over the bits, `fma` the exact `a * b + c` from `integer-decode-float` rounded once by
  `%java-nearest-binary` (`%java-scaled-binary`), `scalb` the same with the exponent moved
  (clamped to +-2200: past it the result is an infinity or a zero). `floatToRawIntBits` and
  `doubleToRawLongBits` are the plain bits: the value model has one NaN (`deviations.md`).

## The class of an array (2026-11-17, j81)

**Invariant: an array's vector tells the class of an array of `int`, `byte`, `char`, `float`,
`double`, `boolean` and `Object`; the class of any other -- of `long`, of `short`, of
references -- is what the run time recorded where its static type was widened, in a program
that can ask it; the store into an array is not checked.**

- **Where it is recorded.** `JavaLowering.widened`: a conversion (`conversion`'s reference arm:
  an assignment, an argument, a return, an upward cast) from an array type `isUntold` to a
  type whose erasure differs, and an argument whose method's DECLARED parameter erases to
  another type (`List<long[]>.add(E)`: the library sees `Object`), wraps the value in
  `(%java-array-widened form '(levels..))`. A level is `(simple-name binary-name
  supertype-names..)` (`arrayClass`, the names as a JDK's `getName` spells them); the levels
  go as deep as the element type is untold, and the run time walks the elements of a
  multi-dimensional array with them (`Object[][] g = new String[2][2]` records the rows). The
  first record wins: the array met its exact type first. `Arrays.copyOf`'s `newArray` and an
  array's `clone()` copy the original's record (`arrayLike`, `%java-array-like`).
- **Who reads it.** `%java-array-class` (the record, else the static type's class when that
  is an array type, else the vector's `array-element-type`), under `%java-guard` through
  `%java-among-threads :array` once a thread was started; `%java-array-is` for a cast and
  `instanceof`, `%java-array-class-name` for `getClass().getName()`/`getSimpleName()` on an
  array type, `%java-class-name`'s and `%java-to-string`'s vector arms for any other.
- **The gate**: `JavaClasses.arrayTypes`, set by a cast or `instanceof` that reads a record
  and by `getClass()` on a type that can hold an untold array (`asksArrayTypes`; the program
  is lowered again). Until then no conversion records, so a program that never asks pays
  nothing and `toString()` of a `String[]` read as an `Object` names `Object[]`
  (`deviations.md`). A session always records. `toString()` does not arm it: every program
  printing an object would record every array it widens and keep it (below).
  `Arrays.deepToString` is Java source over `instanceof`, so a program calling it records.
- **Measured** (2026-11-17, Lisp prototypes; JVM / precompiled wasm, best of two):
  - the store check through an identity table, 10^8 stores into a 1,024-element vector: plain
    0.41-0.48 / 2.42-2.77 s, a table look before each (a miss) 1.35-1.36 / 5.51-5.53 s:
    +9.5 / +31 ns a store. Its gate cannot be narrow: `Arrays.sort(String[])`,
    `Arrays.toString(String[])`, `Arrays.fill` pass an `S[]` as an `Object[]`, after which
    every store through an `Object[]` -- `ArrayList.add`'s own -- may be one into an `S[]`.
    Not made.
  - a record, 10^6 fresh two-element vectors each recorded: 0.15-0.16 / 0.08-0.09 s without,
    0.77 / 2.87-3.23 s with (0.6 / 2.8 us a record at that table size), and every recorded
    array is kept for the program's life: no backend has a weak table, and wasm-GC has no
    weak reference to build one from.
  - a header slot as a displaced view (the class in slot 0 of the target), 10^8 read and
    store rounds: 0.36-0.38 / 6.99-7.07 s plain, 3.03-3.10 / 18.3-18.5 s displaced (8x /
    2.6x). A slot in the backends' own array header (the remembered element type's,
    `.kb/array-literals.md`) is the alternative left: a representation change on four
    backends, its cost unmeasured.
  - sizes: `Fib`, `WrapInt`, `Sieve`, `WrapLong`, `Basics` byte-identical on both targets;
    `Shapes`, which prints an object (`%java-to-string`'s vector arm), 333,863 -> 335,671 B
    of class and 285,005 -> 286,859 B of wasm.
- **A cast is no inference context** (JLS 15.16): `(String[]) Arrays.copyOf(objects, n)`
  infers `T` from the arguments alone; the cast's type reaches only a lambda or a method
  reference (`needsTarget`). Before, the cast's type bounded `T` and the call failed.

## A class as a value (2026-11-20, k30)

**Invariant: a class read as a value is ONE object a type -- `java.lang.Class`, Java source
of the library --, made before anything of the program runs and holding what the program
asks of one; a class compared with a class, or asked its name, is still its name and makes
no object.** What YAVI's schema example asked (`Map<Class<?>, ..>`, `type == Map.class`,
`isInstance`, `cast`, `isAssignableFrom`, `getSuperclass()` to `null`,
`getEnumConstants()`).

- **Which read is a value.** `isClass` (a literal, `getClass()`) on both sides of `==`,
  `!=`, `equals`, or before `getName()` / `getSimpleName()`, is the names (`classRead`,
  `sameClass`; a literal of an array type is its descriptor, of a primitive type its
  keyword, compared as its box). Anywhere else: `classLiteral` -- the variable
  `|Name%class|` (`classValue`), typed `Class<Name>`, a primitive's by its box and
  `void`'s by `Void` -- and `classOf`, `(|T%class-of| value)` typed
  `Class<? extends |T|>`. The parser takes `int.class`, `void.class`, `Name[].class`.
- **The object** (`library/java/lang/Class.java`): the binary name, the simple name, the
  canonical name (`null` where it is the name, empty for a local or an anonymous class),
  a kind (1 interface, 2 array, 4 primitive, 8 enum, 16 record), the class above, the
  interfaces, the component type, and two functions the lowering makes -- the test of an
  instance (`instanceTest` from `Object`, so an interface's is its `Iface%is`, with the
  lambdas it tags) and an enum's `values()` -- which two `native` members call (`test0`,
  `constants0`). `equals` and `hashCode` are `Object`'s, so a class is a map's key by
  identity. `Enum.valueOf(Class, String)` and `getDeclaringClass()` are Java over it.
- **Made once, before the program** (`classObjects`, in `generatedFunctions` ahead of the
  interface tests it asks for; `classInits` at the head of `initialization`):
  `(defvar |X%class|)` and `(setq |X%class| (|Class.new(..)| ..))`, each after what it
  names. Rejected: made at its first read (two host threads of the JVM or the interpreter
  could make two, and identity is what `==` and a map read). What is asked persists over
  the passes (`JavaClasses.classValues`, `classOfs`, `arrayRecords`), as `made` does. A
  session guards the `setq`: an earlier buffer's object stays the class's.
- **An object holds what the program reaches of `Class`** (the names of its methods in
  `JavaClasses.reached`): the class above where `getSuperclass` or `isAssignableFrom` is
  reached, the interfaces for `isAssignableFrom`, the test for `isInstance` / `cast`, the
  constants for `getEnumConstants`; `NIL` otherwise. A program keying a map by classes
  makes the objects of its literals and none above them. A session holds all of it: a
  later buffer may ask what the one making the object did not.
- **`getClass()` as a value** is one function a static type (`classOfFunction`): the made
  classes under the type deepest first, the intrinsic classes under it the program can
  hold (`held`) by their predicates (a `StringBuffer` is a `StringBuilder`'s
  representation: the builder's), under `Object` an instance of `Object` itself and an
  array, then `%java-no-class` -- `null` is the `NullPointerException`, a lambda the
  run-time error `getName()` of one is (a lambda is a function, which names no class).
- **An array's class object is found by the name the run time tells**
  (`arrayClassFunction`, `|%array-class|`, gated by `arrayClassValues`, which arms
  `arrayTypes`): `%java-array-class-name` answers what a conversion recorded, else the
  static type's, else the vector's, and each is one of the seven a vector tells or of
  the array types `arrayClass` was asked (`arrayRecords`) -- so the `cond` over those is
  complete. `getClass()` of an exact array type (`int[]`) is its literal's object.
- **A class object initializes nothing; an enum's constants do.** A literal reads a
  variable; `getEnumConstants()` calls `values()`, whose function initializes. For the
  order of initialization what holds an enum's class object calls its `values()`, as
  what makes a lambda does the lambda's body (`enumValues`: `called` and `uses` at each
  literal of the enum and each `getClass()` it stands under) -- only in a program that
  reaches `getEnumConstants` (`JavaClasses.enumConstants`, lowered again when first
  reached), so that no other program gives its enums a `values()`.
- **`==` of two parameterizations no value has both of** is the compiler's `incomparable
  types: Class<String> and Class<Integer>` (`distinctArguments`: one class, at some place
  two types that are neither wildcards nor type variables and differ; any generic class).
- **Not the language's** (`deviations.md`): no reflection and no `getInterfaces()` (an
  intrinsic class implements the interfaces the program names, `meets`, so the answer
  would be the program's); the class above a library class is this library's
  (`StringBuilder` under `Object`); `synchronized (Name.class)` still holds the
  structure's name, not the object; `getClass()` of a lambda.
- **Measured** (wasm bytes, the base `278f7e03d` against this): a program comparing two
  classes and asking a name 13,811 both ways, `examples/java/shop` 328,295 byte for
  byte, YAVI's classic example 655,275 both ways; a literal held in a variable, asked
  its name and compared 17,078 (the structure of nine slots, its constructor, one
  object); `getClass()` of an `Object` held, with `isInstance` and `isAssignableFrom`,
  32,970 (the function over every held class, the seven arrays a vector tells with
  their components, each with its test and the classes above it); the corpus case
  327,584. YAVI's schema example, its source edited around the five library classes
  then missing, is 1,009,805 and prints a JDK's lines on four backends.

## Static initialization (2026-11-17)

The language initializes a class at its first use. Running every initializer before `main` (the
first slice) is wrong as soon as one prints, or reads what `main` changes first -- silently.
Testing at every use is right and costs each read of an enum constant a call. So
`JavaInitOrder` decides per class, from what the lowering recorded of each function
(`Effects`: prints or throws, static fields read and written, functions called -- every body a
virtual call can run --, classes used):

- **EAGER**, initialized before `main` with no test anywhere, when the initialization with
  everything it can call prints nothing, throws nothing (a `throw` statement), writes no static
  field of another class, and reads of another class only a `final` field whose type nothing
  can change (a primitive, a string, a box, a class of the program whose fields, with those of
  every class above and under it, are final and of such types); when every class it needs first
  (one it makes, calls a static method of, reads, extends) is eager; and when no two need each
  other. Eager classes run in the order they need each other. Why this is sound: by induction
  every reference such code can hold is made during the initialization, held by the class's own
  static fields, or immutable -- so nothing it reads can have been changed by code that ran
  first, and nothing it writes can be read before the class's first use. `final` is checked
  for this reason.
- **LAZY** otherwise: `|C.<clinit>|` runs the initializers once (`|C.<clinit>%done|` set first,
  the superclass's first), called at the top of the class's static methods and of the functions
  making its instances, and around a read or a write of its static fields from a class that is
  not known to be initialized (`initialized`: the class itself, a subclass, a class declared
  inside one of those and not static). The class the program starts at is initialized by the
  entry form and never tested.
- Constant variables are set first. Every static field is `(defvar |C.f| <default>)`, its type's
  default (JLS 12.4: before its initializer, and for a class a read finds not yet initialized);
  so is each `|C.<clinit>%done|` flag (nil), class object and interned string. A `defvar`
  without a value is a special without one, every read of it a test of the UNBOUND marker
  and a call to the `unbound-variable` report ([dynamic-special-variables.md](dynamic-special-variables.md),
  "A read of a special without a value"): a static is never unbound. Measured 2026-11-21 (k67,
  wasm P1): the `Europe/Paris` program (`ZonedDateTime.of(..).plusHours(1)`) 173,105 -> 170,858 B
  (-2,247), `examples/java/shop` 319,781 -> 318,309 B (-1,472; the fields -864, the `%done`
  flags -608). A session's buffer still sets the defaults of a class it declares again. On
  the JVM a valued static is a raw-global candidate, which made a per-global walk minutes
  ([jvm-int-fusion.md](jvm-int-fusion.md), "Entry points beyond plain trees", k81).
- The decision is made at the end of a pass and the next pass is lowered with it.
- **Where it runs** (k63): what cannot throw -- the class objects, the constant fields, the
  defaults a field holds ahead of its initializer, the lazy classes' flags -- is set by
  top-level forms of its own ahead of the entry (`JavaLowering.settings`, after
  `internings`); the eager classes' initializers, in their order, are the body of
  `(defun %initialization ..)`, called by the entry ahead of `main`, so a run-time error
  in one is still reported as what `main` left uncaught. In the entry form they were one
  JVM method, never cut (a loop's exit in it), and a static block of 3,000 statements
  stopped the compile ([jvm-method-size-limits.md](jvm-method-size-limits.md), "Bodies
  bounded by construction"); a defun is cut as any. A program initializing no class
  before `main` is byte for byte as before (a hello: wasm 17,179 B both ways); one that
  does pays the function (`examples/java/shop` wasm 328,166 -> 328,188 B). Only a program
  that runs: a session's buffer and a loaded library keep the initializers in their
  top-level `progn`.
- Classes that need no other run in the order of lowering, so one source compiles to the same bytes
  every time: the per-class and per-method records are insertion-ordered maps, never an
  identity-hashed one (2026-11-20; the same YAVI example compiled three times by one jar is
  one 1,204,017 B `.wasm` and a `cmp`-identical one, `JavaLoweringTest`).
- **An initialization that throws an object ends as the language ends it** (j88): "A static
  initialization that throws" under "Exceptions".
- Not the language's: a run-time error inside an eager initialization surfaces before `main`
  (reported as the JDK reports an uncaught one), and an error the run time raises inside any
  initialization is neither wrapped in `ExceptionInInitializerError` nor remembered by the
  class (stated in `deviations.md`, `.todo/k17`).

## Exceptions (2026-11-17, j73; the entry and the catch: j82)

- **What the run time raises is TEXT, `(error "~A" "java.lang.X: message")`** (`%java-raise`,
  `%java-npe`): a simple error whose format control is that text on four backends (the site
  pre-renders it), no condition class of its own. Rejected: a `define-condition`
  `%java-error` holding class and message -- its `typep` in the entry's handler cost every
  program ~30 KB of wasm ("Measured" below).
- **ONE table from a condition to an exception**, `%java-exception-of` in `java.lisp`:
  `%java-raised-text` (a simple error whose control starts `java.`), `%java-host-text` (a
  `division-by-zero` is `ArithmeticException: / by zero`; a `type-error` of `(integer 0 (n))`
  `ArrayIndexOutOfBoundsException: Index i out of bounds for length n`; a `type-error` whose
  datum is NIL -- a structure's accessor, an array's element read on null -- is
  `NullPointerException`), else `RuntimeException` of the condition's text.
  `JavaLowering.RAISED` lists the classes the table can answer; `JavaLoweringTest` checks that
  every `java.*Exception` `java.lisp` names is in it. **The same three host failures are read a
  second way, off the report TEXT** (`%java-host-report`), where no condition is held -- the
  entry's line, below: `Division by zero`, `OP: The value NIL is not of type T`,
  `OP: The value I is not of type (INTEGER 0 (N))`, the one line every backend reports them by
  ([error-handling.md](error-handling.md), "An uncaught condition reports ONE line"). java-spec
  `common-lisp-and-java-call-each-other` prints both readings of each on four backends.
- **Converted at the CATCH, not where raised.** A clause whose class is above one of `RAISED`
  marks it (`caughtRaised`); the generated `%lisp-error` makes the program's object of the
  table's class by its constructor of a message (or of a message and a cause) for those
  classes only, nil for any other, and a clause below lets the condition pass. So a field read
  costs nothing: no test, the host's type error over NIL is the `NullPointerException`.
  **Raising them at the site instead was measured and not made** (j82, 2026-11-17, IR edited
  by hand, JVM / precompiled wasm seconds): an index test ahead of every `aref` / `%aset`
  (132,000,000 accesses of an `int[]`) 2.28 / 1.58 -> 1.73 / 2.79 -- 9 ns an access on wasm,
  the host's own check being an inline compare already; `(if (eql d 0) ..)` ahead of
  `truncate` and `rem` (4*10^8 divisions) 3.39 / 7.63 -> 3.42 / 8.68, 2.6 ns a division on
  wasm; `(or p (%java-npe))` at a field read (4*10^8 reads) 0.72 / 1.81 -> 0.69 / 1.93, free.
  The array test alone decides it: the host's conditions stay what a Java program raises.
- **A `catch` is a `handler-case` clause of type `(satisfies %catch<n>)`** (j82):
  `JavaLowering.catchTest` generates one test per set of classes,
  `(defun %catch0 (c) (A? (or (%java-thrown c) (%lisp-error c))))`, and the clause binds its
  parameter to that object again. A condition no clause takes is not signalled again: the
  landing passes it on as it came, so the entry's location lines name where it was raised.
  Before, one clause on `error` ended in `(error c)` -- a computed datum, on which the
  condition narrowing bails ([error-handling.md](error-handling.md), "The condition floor"),
  so a program with any `try`/`catch` carried every condition class. Rejected: a
  `handler-bind` whose handler exits for a class it takes (85,071 B of wasm against this
  shape's 81,402 on the same IR, and 26 ns a `try` entered on both backends: 0.78 / 1.37 ->
  3.39 / 3.98 s over 10^8 rounds, where this shape measures 0.73 / 1.35).
  Raised at the site, where the host would say nothing or the wrong thing: a call going
  straight to a body through a reference that can be null, an intrinsic's receiver and an
  array's length (`(or x (%java-npe))`; `this`, a literal, a `new`, a concatenation are not
  tested; a dispatcher's last arm answers null) -- measured free (a direct call per node of a
  list walked 10^8 times: JVM 0.47 -> 0.45 s, wasm 1.02 -> 1.03 s); a negative array length
  (`%java-array-length`, of `new T[n][m]..` once every length is evaluated); `charAt` (the host's
  `char` checks nothing compiled: the JVM gave a `type-error` of datum NIL, wasm a garbage
  code), one read answering -1 outside the string ("`charAt` is one read" below); a failed
  cast's message,
  `class A cannot be cast to class B` from `%class-name` (none for a lambda), without the
  modules and loaders a JDK adds.
- **A `NullPointerException` the run time raises has no message.** A JDK's describes the null
  expression by its class file's names: `"<local4>"` under the source launcher, `"p"` under
  `javac -g` (measured): no one text to match.
- **Uncaught: the entry is `(%reporting-uncaught #'%java-uncaught-line (%host-exit 1) init.. start)`**
  (j82), the pipeline's own report of an uncaught condition with the program's line in it
  ([error-handling.md](error-handling.md), "A program's own line"): the function is called
  with the condition instance the backend has at hand, or nil, and the condition's report
  text, and answers `Exception in thread "main" <text>`; the location lines follow
  (`  at File.java:4 in Uncaught.check(int,int)`: the interpreter and the JVM, wasm under
  `--report-locations`); then `%host-exit 1` -- on the JVM `System.exit`, so a compiled Java
  class invoked in process ends its JVM, as `System.exit` does (`UncaughtReportParityTest`
  runs compiled classes in process, hence no Java case there: `JavaSpecE2eTest`'s
  `standalone:` legs run each in a process, and check the location line by `at:`). The text:
  one starting `java.` is the run time's own; a host failure is read off its report
  (`%java-host-report`, above); any other error of rontolisp's own is `RuntimeException` of
  its text. `%java-uncaught-thrown-line`, in a program that throws objects, takes a
  `%java-throwable`'s report -- its `toString()` -- as it is; that `typep` is why the function
  takes the instance: a `toString()` may answer any text, a host report's included. The text
  is read with `%string-search` alone (`%java-text-at`): `search` brought its keywords and
  sequence arms (+10.6 KB of wasm, +15.6 KB with `:start2`), a loop over the characters its
  signal sites (+11.5 KB in `Fib`, which has none of its own).
  No handler holds the condition, so no signal site builds one: that was the j73 entry's
  cost ("Measured" below). A `return` in tail position is located as the statement it is,
  so the line names the statement that raised. A program that starts threads keeps
  `%java-thread-main` and its `handler-case` (measured and kept: "Threads").
- **What nothing caught is reported with what it encloses** (j88): the lines
  `printStackTrace()` writes -- `\tSuppressed: `, `Caused by: `, `[CIRCULAR REFERENCE: ..]`
  -- under the first, then the pipeline's location lines. `Throwable.trace(Throwable)`
  (private, static) is that text; a thread's report asks it always
  (`%java-traced-text`, the third argument of `%java-thread-main`), the entry of a program
  that starts none through a generated `(defun %uncaught (%c %text) ..)` handed to
  `%reporting-uncaught`, and only where an exception can enclose another
  (`JavaLowering.enclosing`: a constructor of `Throwable` taking a cause, `initCause` or
  `addSuppressed` is reached). So a program whose exceptions have no cause carries no trace
  (three `try`/`catch`: 59,949 B of wasm, some 2 KB more with one). `MatchException` has a
  private constructor of a message for `%lisp-error`: its only API constructor takes a
  cause, and a `catch (RuntimeException e)` reached it. The trace is built by string
  concatenation over exceptions numbered by the trace that wrote them
  (`Throwable.traces`): a `StringBuilder` brought the text of every kind of value (the same
  program 140,544 B), an array of the exceptions seen made the three functions 3.6 KB,
  marks taken back after the trace 1.6 KB, the numbers 1.2 KB. Two threads tracing at once
  read each other's numbers.
- **`try`-with-resources is written by the parser as JLS 14.20.3.1 spells it**
  (`JavaParser.withResources`: the resource, a `%primary` local, `catch (Throwable)` keeping it
  and rethrowing, a `finally` closing): the lowering sees only `Close`, which checks the type
  (`try-with-resources not applicable to variable type`), and closes when not null, an
  exception of `close()` suppressed by the body's. A resource that names a variable is declared
  under a name of its own (`%resource<n>`). Multi-catch: `(or (A? x) (B? x))`, the parameter
  the lub, alternatives related by subclassing refused with javac's message.
- **`return`/`break`/`continue`/`yield` inside `finally`** are a non-local exit out of an
  `unwind-protect` cleanup: CL's newer exit wins, which is Java's finally discarding what the
  `try` did. wasm inlined a named `return-from`'s cleanups inside the handlers it left; fixed in
  the backend ([error-handling.md](error-handling.md), Phase 1).
- **`Throwable`** (source): a cause set once (`causeSet`; `initCause` refuses a second and
  itself, with a JDK's messages), suppressed exceptions in an array, `printStackTrace()` writing
  the exception, `\tSuppressed: ` and `Caused by: ` lines and `[CIRCULAR REFERENCE: ..]` as a
  JDK does, without frames (`java-spec.yaml` `standalone:` with `stderr:`). The four constructors
  where the API has them; `IndexOutOfBoundsException(int)` and kin.
- **A static initialization that throws** (j88; JLS 12.4.2, the answers a JDK 25's): a
  lazy class whose initialization can end with a thrown object
  (`JavaLowering.failingInitializations`: it reaches a `throw` -- `Effects.thrown`, set by
  `raise` -- or it initializes a class that can, its superclass included) is
  `(if (eq done t) nil (if done (%java-throw (NoClassDefFoundError.unusable "C" done) ..)
  (progn (setq done t) (handler-case (progn super.. inits..) ((satisfies %java-thrown) (c)
  (let ((e (%java-thrown c))) (setq done (ExceptionInInitializerError.record e "main"))
  (%java-throw (ExceptionInInitializerError.of e) ..)))))))`: an exception that is no
  `Error` is the cause of an `ExceptionInInitializerError`, an `Error` passes, and the class
  keeps IN PLACE OF ITS STATE the text a JDK gives the cause of the later
  `NoClassDefFoundError` (`Exception <toString()> [in thread "<name>"]`). In a program
  that starts threads the text is written under the initialization lock where `:ended`
  would be, `%java-init-busy` reads a string as ended, and the name is
  `Thread.currentName()`. A class that cannot throw keeps the two-state test, so nothing
  else pays. Three classes were added for it (`LinkageError`, `NoClassDefFoundError`,
  `ExceptionInInitializerError`). **What the run time raises is not wrapped**: it is no
  object until a `catch` makes one, and a clause taking it would make every program with a
  lazy class hold its condition and carry the classes of `RAISED` (the j73 entry's cost,
  40 KB of wasm); such an error passes as it came and the class stays as far as it got.
  Cost: `examples/java/shop` (one such class, threads) 303,997 -> 309,806 B of wasm with the
  trace, 418,108 -> 412,215 B of class. **Marking the class unusable where the run time's
  error leaves its initialization was measured and not made** (j99, 2026-11-18, the lead
  of an `unwind-protect` with no handler, spelled in Java over a lazy class in a program
  that throws nothing; wasm / class bytes): as lowered today 18,744 / 34,421; a `finally`
  setting a flag 18,828 / 34,732; a later use throwing `NoClassDefFoundError` 50,092 /
  77,358. The mark is free; the 31 KB are the thrown object at the later use, the j73
  entry's cost again. What it would buy without them -- the later use raising the run
  time's TEXT -- is a `NoClassDefFoundError` with no cause, after a first error that is
  still not wrapped: `.todo/k17`.
- **`charAt` is one read** (j88): `(let* ((c (%string-code s i))) (if (< c 0)
  (%java-string-index i s) c))`. `%string-code` (`LispNames.STRING_CODE`,
  [string-index-cost.md](string-index-cost.md)) answers the character's code, -1 outside
  the string: a function on the interpreter, the public reads on the JVM, one call of
  `_str_code` on wasm. Measured on the loop over 26 letters 10^6 times (JVM / wasm,
  interleaved, best of five): 0.26 / 1.25 -> 0.26 / 0.61 s -- under the 0.76 s the loop
  took outside EH mode (j73). What it replaced paid a generic `length` call for its bounds
  test, `_idx_chk`, the operator register and `_str_char_ref`'s tests at each read. The first expansion on
  the JVM bound both operands and cost 0.30 s: a variable is read where it stands, so one
  the backend keeps unboxed stays so.
- **What a program that throws carries** (j88, `Fib` 2,868 B against 60,903 B for a loop
  calling a method with one `throw`, no `try`; `-Drontolisp.wasm.debug-func-sizes`): the
  prelude's `describe` with `type-of`, `pathname` and what they call, 10.5 KB of wasm and
  41 KB of class, kept by NOTHING BUT A NAME -- `%java-throw`'s parameter and the
  condition's slot were called `describe`, and the prelude keeps a function a program names
  in any position (`LispPreludeLibrary.process`, `symbolNames`); renamed, and
  `JavaLoweringTest#theLoweringTable` holds java.lisp to binding no such name.
  `%java-class-name` with the class of an array, 3.8 KB, for `Throwable.toString()`'s
  `getClass().getName()`: `%class-name` now ends in the run time's answer only where it is
  asked of a value that can be something other than an instance of a class of the program
  (`namesOfAny`: `Object`, an interface, an intrinsic, a failed cast's message), else in
  `%java-no-receiver`. What is left, 46,765 B: the print-object seam every program that can
  build a condition gets (`%pos-walk`, `%pos-instance`, `%pos-chain-stop`: 8.8 KB), the
  printer (`_princ_val`, `_print_val`, symbol escapes: 7.7 KB), the report
  (`%condition-report-str`, `%signal-text`, `%seq-to-string`, `%subseq-runtime`, the string
  stream: 8.5 KB), the rest the program's classes and the run time. The first three are the
  pipeline's price for a condition class with a `:report` ([error-handling.md](error-handling.md),
  "A condition's `:report` is what PRINTS it"), `.todo/k17`'s lead.
- **The run time's errors are the library's classes at every place a program can see one**
  (j88, probed against a JDK 25 on four backends): class, message, cause, `toString()`,
  the same object thrown again and as another's cause, through two clauses and a
  `finally`, to an uncaught-exception handler -- all a `catch` -- print a JDK's lines but
  the two stated messages. Nothing was changed; java-spec
  `a-lisp-error-reaches-the-catch-of-the-exception-it-is` holds the kept object.
- **Checked exceptions are checked** (j99, 2026-11-18; JLS 11.2, every message, position
  and order asked of javac 25 over 136 probe programs, 132 of them answered alike, kept
  with the four that are not in
  `.todo/artefacts/j99-java-front-end-what-the-charat-initializer-and-causes-slice-left/`).
  Three parts:
  - **The clauses.** The parser keeps `throws` (`JavaSyntax.Method.thrown`), the class
    table resolves it (`JavaClass.Method.thrown`: classes and type variables; a type that
    is no `Throwable` is `incompatible types: String cannot be converted to Throwable`).
    An intrinsic member names its classes (`Method.raises`, `Builder.throwing`: `wait`,
    `getBytes(String)`, `new String(byte[], String)`), read where a call of it is
    lowered so no program holds them for nothing. Resolving a clause where its class is
    declared loads the exception classes earlier than before: the ORDER of the class
    table moved (structures, eager initializations), nothing was added -- of 25 programs
    measured 17 kept their size, 8 moved by at most 43 B of wasm and 14 B of class
    either way, and one whose only exceptions are in clauses is byte-identical. **What
    makes the check safe is
    `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles`**: every public method and
    constructor of the running JDK's class of each library class's name is asked by
    reflection which checked exceptions it throws, and the member a call reaches here
    (own or inherited, by erased signature) has to declare the same. Over 2,000 members
    compared; the five it found were the intrinsics above. 117 JDK members have a
    namesake here of the same arity and other parameter types: overloads the library
    lacks (a `CharBuffer`, a file name), refused where a program calls them.
  - **Typing** (`JavaLowering`, an error of the class before any flow): an override
    throwing more (`m() in C cannot override m() in B; overridden method does not throw
    X`, `cannot implement` for a class's method under an interface's;
    `overriddenThrows`), a method inherited from the superclass implementing an
    interface's (`inheritedThrows`, said at the class's keyword), and a method
    reference whose method throws what its function type does not (`incompatible thrown
    types X in functional expression`, at the reference's start).
  - **The flow** (`JavaFlow`, kind `THROWN`). The lowering records by node what each
    call throws (`Facts.raises`: the clause through the call's site and the type
    arguments found, `thrownBy`; a `throw`'s static type; a catch clause's classes; what
    a lambda's function type lets through, `lets`), the walk keeps a list of
    `(type, position)` per body. `try`: a list of its own; a clause takes what is under
    its classes, its parameter then HOLDING those (and its own class where the block
    throws a superclass of it), which is what `throw e` throws while no assignment of
    `e` is in the clause (asked by a walk of the clause's body that reports nothing,
    `reassigns`); what is left and what the clauses throw join the list around unless a
    `finally` cannot complete normally. Roots: a method's and a constructor's clause, a
    lambda's function type, nothing for a static initialization; instance initializers
    are walked with every constructor; an anonymous class's throw where it is made, less
    what the constructor it hands its arguments to throws (the creation throws that).
    A step of a session throws what it likes, as a jshell snippet does.
  - **What the answers showed that the specification does not say**: the position is a
    call's `(`, a creation's `new`, a `throw`, the body's `{` for a `super()` not
    written, the class's keyword for `in default constructor`, the resource's variable
    for `close()`, the `catch` for a clause and the alternative for a multi-catch. In
    one body the clauses' errors and a lambda's come where the walk meets them and the
    body's own at its end. `exception X has already been caught` is the flow's, for
    unchecked classes too. A clause all of whose exceptions were taken before is a
    WARNING (`unreachable catch clause`, printed with `-Xlint:none` too): through
    `JavaWarnings.warn`, on the unchecked warnings' path.
  - **A type parameter in a `throws` clause** (JLS 18.1.3, 18.2.5, 18.4): one nothing
    says anything of is `RuntimeException` where its bounds allow (`settled`: no checked
    exception), else its bound; a lambda or a method reference given for a function type
    whose clause names it bounds it from below by what its body or its method throws
    (`throwsOf`, after the argument is lowered; `JavaFlow.thrown` walks the lambda's body
    alone), several by their lub. Read from the parameter's DECLARED type: the type the
    inference has so far would fix the parameter at the first lambda's exception. What
    the lambda may throw is recorded again once the call's solution is known. javac's
    inference reads a catch parameter of a clause AROUND the lambda as its declared
    type, not what it holds (`call(() -> { throw e; })` is `unreported exception
    Exception`), so the walk of a lambda alone does the same; an anonymous class's
    initializer reads it as what it holds (`Facts.rethrown`, `holds` walking the `try`
    block where the walk did not come through it).
  - **A charset's name written as a constant** makes the lowering call the member taking
    the `Charset` (`charsetsNamed`); the call still throws what the member taking the
    name does (`UnsupportedEncodingException`), or a valid `catch` of it would be
    refused.
  - **Not as javac**: `Object.clone()` and `finalize()` are no members here
    (`.todo/k16`). A type variable thrown is named without the `where` lines javac adds:
    an error is one line (`error: File:line:col: message`, no source line or caret under
    it), so no error carries what javac writes under its first line, while a warning is
    printed whole (`JavaWarnings`) -- not a gap of this message (k06).
- **An exit a cleanup takes** (j99): `try { return a(); } finally { return 4; }` is
  `(block f (unwind-protect (return-from f ..) (return-from f 4)))`, which the JVM backend
  compiled without end -- each exit inlined the cleanup with its region still open
  ([error-handling.md](error-handling.md), Phase 1). The first corpus program with a
  `return` in both met it.
- **Measured** (2026-11-17, Linux x86-64, GraalVM JDK 25.0.5, wasmtime 49, best of three; JVM /
  precompiled wasm seconds, then `.wasm` / `.class` bytes; before -> after):

  | program | before | after |
  |---|---|---|
  | `Fib` | 0.25 / 0.21, 2,728 / 20,743 | 0.23 / 0.23, 41,656 / 61,620 |
  | `WrapInt` | 0.23 / 0.14, 2,508 / 21,992 | 0.24 / 0.13, 41,536 / 62,675 |
  | `Sieve` | 0.67 / 0.51, 10,819 / 37,363 | 0.66 / 0.56, 48,675 / 74,169 |
  | `WrapLong` | 0.16 / 0.07, 2,539 / 21,531 | 0.16 / 0.07, 41,465 / 62,204 |
  | `charAt` over 26 letters 10^6 times | 0.26 / 0.76 | 0.28 / 1.44 |
  | a `try` around a call, 10^8 rounds | 1.68 / 1.17 | 1.79 / 1.19 |
  | the same with no `try` | 0.40 / 1.04 | 0.41 / 1.12 |

  The size is the handler holding its condition: once a program can hold one, every signal
  site builds one (`.kb/error-handling.md`, "The routing gate"). On `Fib.wasm`: the handler
  and `%host-exit` alone 13,041 B; `write-string` to `*error-output*` instead of `%warn` +7 KB;
  `type-error-datum` +23 KB (+2.5 KB in a program with a signal site of its own); the first
  design (a `%java-error` class, `princ` for the rest) 52,201 B. The `charAt` loop pays EH
  mode (~0.4 s: `_str_char_ref`'s string test in EH mode, the old forms under a `handler-case`
  1.18 s) and the index test (~0.25 s). A `try` in a loop costs the JVM ~13 ns a round (the
  `handler-case`), wasm nothing measurable.
- **Measured after j82** (2026-11-17, same box and method; `.wasm` / `.class` bytes, before ->
  after; the times did not move: `Fib` 0.19 / 0.19, `Sieve` 0.63 / 0.55, the `charAt` loop
  0.27 / 1.38, a `try` in a loop 0.69 / 1.39 s):

  | program | j73 | j82 |
  |---|---|---|
  | `Fib` | 41,656 / 61,704 | 2,868 / 25,005 |
  | `WrapInt` | 41,536 / 62,767 | 2,648 / 26,275 |
  | `WrapLong` | 41,465 / 62,298 | 2,679 / 25,806 |
  | `Sieve` | 48,675 / 74,257 | 22,493 / 42,208 |
  | the `charAt` loop | 42,015 / 63,331 | 12,747 / 28,264 |
  | a loop calling a method that throws, no `try` | 64,572 / 120,614 | 60,901 / 118,957 |
  | the same with a `try`/`catch` in the loop | 183,852 / 200,403 | 64,019 / 119,680 |
  | three `try`/`catch`, one taking a host error | 199,737 / 244,147 | 79,463 / 165,028 |

  `Sieve` over `Fib` is EH mode with signal sites: 8,639 B with no entry at all, 19,522 B
  with an `unwind-protect` around `main` (IR edited by hand). The programs and how each
  variant was made: `.todo/artefacts/j82-java-front-end-what-the-exceptions-slice-left/`.
- **Measured after j88** (2026-11-18, same box and method; `.wasm` / `.class` bytes, before
  -> after; `Fib`, `WrapInt`, `WrapLong` and `Sieve` are byte-identical):

  | program | j82 | j88 |
  |---|---|---|
  | the `charAt` loop | 12,747 / 28,168 | 12,442 / 27,962 |
  | a loop calling a method that throws, no `try` | 60,903 / 118,894 | 46,765 / 72,177 |
  | the same with a `try`/`catch` in the loop | 64,021 / 119,617 | 47,861 / 73,210 |
  | three `try`/`catch`, one taking a host error | 79,465 / 164,965 | 59,949 / 97,267 |
  | a counter under four threads | 100,630 / 174,751 | 88,642 / 125,415 |
  | `examples/java/shop` | 303,997 / 418,108 | 309,806 / 412,215 |

  The programs and how each variant was made:
  `.todo/artefacts/j88-java-front-end-what-the-uncaught-report-and-catch-slice-left/`.
  What is left of the list j73 made is `.todo/k17`'s: the host's failures in an
  initializer, `StackOverflowError`, the `NullPointerException` message, the frames.
- **`getStackTrace()` answers an empty array, after `setStackTrace` too** (j98). A slot
  of `Throwable` holding what a program set was 275 B of wasm in every program that
  throws (a list program 124,032 -> 124,287, one catching its own exception 56,768 ->
  57,043), for a set-then-get no program in the repository writes: `setStackTrace`
  checks its elements and keeps nothing (`deviations.md`). `StackTraceElement` is a
  class a program makes; the classes a program can now name beside its own
  (`AssertionError`, `ArrayStoreException`, `StackOverflowError`, `OutOfMemoryError`,
  `SecurityException`, `FileNotFoundException`, `InputMismatchException`) are raised by
  nothing of the run time.

## Threads (2026-11-17, j76)

**Invariant: a thread is the host's where the target has threads (the interpreter, the JVM)
and a body suspended and resumed where the target has one stack (wasm, both targets): there
the lowering makes the functions a thread can wait in resumable, and a program whose threads
all wait is refused by name with the status 1, never gone on from.** A program whose threads
wait for one another prints the same on four backends ("Resumable bodies" below; until k00,
2026-11-18, a `.wasm` ran a started thread on the stack of the thread that could not go on
without it, which ran 11 of the 16 programs below).

- **Where it sits.** `Thread` is Java source (`library/java/lang/Thread.java`: names, the
  daemon flag, `join` as a loop of `wait` on the thread, the exceptions) over `native`
  members that are `java.lisp`'s "threads" section, which has THREE variants under the same
  names: `#-rontolisp-wasm` (the host's threads), `#+rontolisp-wasm` with
  `#-rontolisp-component` (one stack, the program's own loop over its threads: Preview 1
  and `--native`) and `#+rontolisp-component` (one stack, the host's events giving the
  turns; "The host's events give the turns under a component" below, k07).
  `JavaLibrary.forms(Features)`, `CompileFrontend.expand` passes its features, the
  interpreter reads the host one, the pruner takes the names of all (`everyVariantForms`). The wasm variant has, beside each function
  a thread waits in, the one it is SUSPENDED in (`%java-suspending-..`), which only the
  resumable lowering names. `Object.wait`/`notify`/`notifyAll` are
  intrinsics calling `Thread.await`/`signal`, which throw (`IllegalMonitorStateException`,
  `InterruptedException`): the run time answers a status, so `RAISED` did not grow and a
  `catch (Exception e)` of a thread-free program makes no new class.
- **The host variant**: a CARRIER per thread, `#(thread mutex condition interrupted monitor)`,
  the value of `%java-carrier` -- bound in the thread by `make-thread`'s alist, made at the
  first ask for the thread the program started on ([threads.md](threads.md)). `sleep` and
  `wait` park on the carrier's own condition variable until a ticket is taken, the thread is
  interrupted or the time is up, so `interrupt` is a flag and a notify of that variable and
  needs no host interrupt. The threads that are no daemons are kept and joined at the end.
- **Sixteen small programs are the measure** (fork/join, a synchronized counter,
  producer/consumer, a gate, a hand-off, a worker pool, names, an uncaught exception,
  polling with `sleep` and with `yield`, sleeping then reading, staggered sleeps, a bounded
  buffer, ping-pong, a loop stopped by `interrupt`, one stopped by a `volatile` flag), each
  against the JDK 25's output: the interpreter and the JVM print it for all 16. On wasm and
  the component REFUSED AT COMPILE TIME runs 0; ONE STACK (j76 to j89: a started thread
  queued, run to its end on the stack of the thread that waits, sleeps, yields or ends)
  printed the JDK's output for 11, refused 2 when they ran, never ended for 2 and printed
  the staggered sleeps in another order; RESUMABLE BODIES (k00) print it for all 16 on both
  targets (`.todo/artefacts/k00-java-front-end-a-thread-suspended-and-resumed-on-wasm/sixteen/`;
  j76's own were not kept). The corpus's `threads-wait-for-one-another-again-and-again`
  holds what one stack could not run.
- **A program that starts no thread has one** (wasm): the lowering is not resumable
  (`JavaLowering.startsThreads`: no call reaches `Thread.start()`), `sleep` passes the
  time, `yield` does nothing, a `wait` with a limit sleeps it and one with none is refused
  by name (`%java-stuck`). Nothing of the scheduler is named, so such a program holds no
  state machine: `Fib`, and a program reaching no member of `Thread`, are byte-identical
  to what they were.
- **A monitor lives in a side table keyed by identity, not in a slot** (measured, Lisp
  prototypes of both, 20,000,000 uncontended rounds, JVM / wasm): no lock 0.31 / 0.22 s,
  the table 1.28-1.52 / 3.3-3.5 s, a slot 1.06-1.21 / 2.4-2.6 s; and 50,000,000 objects never
  locked, two slots against three: 3.2-3.6 against 3.6-3.7 s on the JVM, 16.8-17.1 against
  18.9-19.5 s on wasm (44 ns an object). So a slot costs every object for 10 to 45 ns a
  `synchronized`, and cannot hold the monitor of an array, a string or a `new Object()`: the
  table. A thread keeps the monitor it entered last (carrier slot 4), so one lock taken again
  and again is found with no look in the table. A table past 4,096 monitors drops one at its
  last exit when nothing waits in it, said under the monitor's own mutex (slot 4 nil), which
  a thread that found it earlier reads after taking that mutex -- an object locked once is
  not kept for ever, and more than 4,096 objects locked in turn pay a monitor made each time
  (at 256 the bound cost 1,000 objects 337 against 135 ns a round on the JVM). A weak
  identity table in the backends would retire the bound, and the identity hash's retention.
  The table's size is a global of its own (`%java-monitors-held`, k07): asking the table at
  every last exit was 30 of the 170 ns an entry and its exit take on wasm (2026-11-19, the
  counter of `bench/` with its loop a plain function: 1.65 s with the monitor, 0.26 s
  without, 1.33 s with no size asked). What is left is the vectors: some twenty `aref` /
  `%aset` of a general vector a pair (`.todo/k20`).
- **A program that cannot tell a monitor held from one that is not holds none.** Until a
  pass reaches a member of `Thread` (`JavaClasses.monitors`; the program is lowered again when
  one does) `synchronized` is the null test alone and a `volatile` field any field: a class
  that is only thread-safe costs nothing (`synchronized` methods and no thread: 64,060 B of class / 42,587 B of wasm
  against 64,015 / 42,581 without the modifier; 70,820 / 56,280 holding monitors).
- **`volatile` is an access under one lock** (`(with-mutex ((%java-volatile)) access)`, the
  object and the value bound first): the JVM backend's globals and slots are plain fields
  a JIT may read once for a loop, and a lock is what the pipeline has to order two
  accesses (bt2's atomic integer is the precedent, [threads.md](threads.md)). The atomics are
  Java source whose methods are `synchronized`, for the same reason.
- **Static initialization in a program that starts threads is the class's own** (j83). A
  class's `<clinit>%done` is `nil`, the carrier of the thread initializing it, then `:ended`,
  read and written under one lock no initializer runs under (`%java-init-enter` / `-leave`):
  `(if (eq done :ended) nil (let ((self (%java-self))) (if (eq done self) nil (progn (enter)
  (while (%java-init-busy done)) (if done (leave) (progn (setq done self) (leave)
  (unwind-protect body (progn (enter) (setq done :ended) (leave t)))))))))`. A thread asking
  for a class another initializes waits on one condition variable (`%java-init-busy`), the
  thread initializing it asking again goes on, and an initializer that throws still ends the
  class. On wasm the wait is a suspension (`%java-suspending-init`) in an initializer that
  can itself suspend, which is the only one another thread can find under way; any other
  initializer's `%java-init-busy` waits for nothing and refuses by name what it did not
  foresee. Until j83 it was
  `(with-mutex ((%java-initializing)) ..)`, ONE lock for every class: an initializer joining
  a thread that initialized another class never ended on the interpreter and the JVM
  (measured: 20 s and killed; the corpus case's `Loud`).
- **The entry of a program that starts threads** is `(%java-thread-main main finish trace)`:
  what `main` leaves uncaught is reported with what it encloses, the thread's object ends
  (`Thread.finish`), the threads that are no daemons end, then the status. A thread's body is
  `(unwind-protect (handler-case (Thread.body t) (error (c) (Thread.report t text))) (Thread.finish t))`.
  **The two handlers hold their condition, measured and kept** (j88, 2026-11-18, IR and
  java.lisp edited by hand): with neither, a counter under four threads is 92,394 ->
  86,755 B of wasm and 127,038 -> 120,357 B of class (6%, 5%), 92,850 -> 89,597 B once the
  program has one `catch` of its own (3.5%); taking one of the two away saves 0.5 KB, and
  reading the report's text in the handlers instead of the condition's slots 0.4 KB -- the
  cost is the held condition, which every signal site then builds. Not holding it needs
  `%reporting-uncaught` as a form inside a program, where a thread ends and the program
  goes on: a new form on four backends for 3.5 to 6% of a program that starts threads.
- **The run time's own tables** (the identity numbers, `intern`) are read under
  `%java-guard` once a thread was started: `%java-shared` holds the function doing it
  (`%java-among-threads`), set by `%java-thread-start`, so a program starting none
  references no mutex.
- **`System.exit` on a started thread**: the interpreter's exit ends the thread it is called
  on (`eval/LispExitSignal`), so the same function records the status and
  wakes the thread the program started on, which exits where it parks or at the program's
  end (`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot`; it waited
  for ever in `join`). The compiled backends end at the call.
- **Measured** (2026-11-17, the machine and method of "Measured" below; ns a round over the
  loop alone, in a program reaching `Thread`; javac's class / this front end's JVM class /
  wasm): `synchronized` over one object 19 / 50 to 60 / 90, over 1,000 objects in turn
  18 / 121 to 134 / 172 to 180; a `volatile` read and write 6 / 24 / 1. The same loops in a
  program reaching no member of `Thread` cost what the loop without the keyword costs. `Fib`, `WrapInt`, `Sieve`, `WrapLong` are byte-identical on both targets;
  `Shapes` +226 B of class and +16 B of wasm (the test in the identity hash). A counter
  under four threads is 168,841 B of class and 98,004 B of wasm.
- **Stated in `deviations.md`**: the turns the threads of a `.wasm` take (a loop that
  never waits and reads nothing another thread writes keeps its turn; threads that all wait
  end the program with the status 1); what is shared with no monitor is the
  host's; what a pool is not (`getQueue()`, `defaultExecutor()`, an accumulator's one
  value, a fork/join pool's parallelism and its rethrown exception); a
  `ConcurrentHashMap`'s long chain; a thread notified in `wait` still `WAITING` while it takes its monitor again; the handlers not asked for `main`'s thread; a field
  updater of anything but class literals and a constant name; thread numbers are ours; a class literal as a `synchronized` statement's
  lock is the quoted structure name, the object a `static synchronized` method holds, and not the class object a variable holds ("A class as a value").
- **`getState()` answers `BLOCKED` (k07, 2026-11-19).** `Thread.blocked0(carrier)` is
  `%java-thread-blocked`: on one stack whether the carrier is among the threads its
  monitor's last exit lets go on (the monitor's seventh slot, the monitor carrier slot 4);
  on the host carrier slot 5, which `%java-monitor-enter` sets around its
  `mutex-acquire` ONLY when it found the monitor another thread's -- set at every entry, a
  thread running `synchronized` blocks would answer `BLOCKED` to a reader for the two
  stores' time. The owner is read with no lock: a state is a moment's. Pinned by the
  corpus's `Turns` (`blocked 2`: a thread polls another's state while it holds the monitor
  that one enters).
- **Pinned by** `java-spec.yaml`'s `standalone:` thread cases (a `standalone:` case so
  that the corpus program starts no thread and keeps the thread-free lowering),
  `JavaSpecE2eTest#aProgramWhoseThreadsAllWaitIsRefusedWhereThreadsTakeTurns` (the two
  wasm legs), `JavaLoweringTest#aMonitorIsEnteredAndLeftAndAThreadIsTheRunTimes` (the
  host's forms, then the resumable ones) and
  `#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`.

### In the browser (2026-11-19, k10)

**Invariant: the browser's interpreter starts no Java thread. A whole program that can
start one is handed by `PlaygroundRepl.run` to its `OneStack` -- the page compiles it as
`rontolisp Main.java -o main.wasm` does and runs the module in the engine the image itself
runs on, where its threads take turns ("Resumable bodies" below) -- and a buffer of the
Java session refuses `start()` in words.**

- **The cause.** Web Image has one thread and no stack to switch to:
  `Target_AsyncRuntime.spawnThread` runs a body to its end inside `make-thread`
  ([threads.md](threads.md)), so a thread that waits -- a pool's worker asking its queue
  for the next task -- waited for a notify nothing could send, and
  `RontoConditionVariable.await` never returned. Measured 2026-11-19 (GraalVM 25.0.5
  `svm-wasm`, node 24.22, `chrome-headless-shell` 1243): `doc/*/java/index.md`'s pool
  example did not answer in 60 s with one core busy, and froze the documentation page,
  whose cells ran on the page's thread; the HTTP client example froze at its `sendAsync`
  the same way; the counter example (threads that never wait) printed.
- **The seam** (`eval/PlaygroundRepl`): `run` reads the program for the interpreter, and
  when the host gave it a `OneStack` and `JavaLibrary.startsThreads(forms)` -- the forms
  name `%java-thread-start`, which they do only when a call reaches `Thread.start()`, the
  test `JavaLowering.startsThreads` makes -- answers what `OneStack.run(source)` shows,
  after what the read wrote on the page (the warnings). Only the `web` bootstrap gives one
  (`RontoPlayground.runCompiled`): a JVM host keeps host threads. A session step of the
  Java pick runs inside `LispEvaluator.refuseThreads(reason)`, so `make-thread` signals
  there and the step's own handler shows `Exception java.lang.RuntimeException: a thread
  is started only by a whole program in the browser ..`; the other picks keep
  `make-thread` (the body run inside it: clack's `:use-thread`, Scheme's
  `parallel-execute`). The playground page's "Run source" calls `rontoRunProgram` for the
  Java pick -- a session only DECLARES a `main`, so until k10 the button printed nothing
  for a Java program.
- **The module's host** (`src/web/java/.../web/BrowserModule`, JavaScript in the image, so
  every host of the image has it: the playground's worker, the documentation site's, a
  deck's, node): `wasi_snapshot_preview1` over what a page has -- standard output and
  error collected in the order written, standard input the cell's, no file, `poll_oneoff`
  of a clock blocking by `Atomics.wait` on a buffer of its own where the page may and by
  watching the clock otherwise. An import it has not is refused by its name before
  anything runs; a trap's text follows the output.
- **Weighed and not taken.** The interpreter lowering as the wasm backends do
  (`JavaResumable` and the wasm variant of `java.lisp`): the interpreter's `async-defun`
  is a virtual thread a body, so on one thread an `await` of a future nothing settled
  cannot return to the scheduler -- it needs a second, resumable evaluator or a
  continuation-passing pass over the core forms, which is what `WasmAsyncEmit` is to the
  backend. Stacks of the browser's under the image (JSPI): the glue between a promising
  export and a suspending import is Web Image's own, every per-thread state of the
  evaluator (thread locals, dynamic bindings, the handler stacks) would be swapped at
  each switch, and not every browser has it. A started thread queued and run where its
  starter waits: j76's one stack, 11 of the 16 programs.
- **Measured** (2026-11-19, the pool example, a 404,263 B module byte-identical to the
  CLI's for `Main.java`): 4.5 s from the call to the output in node and 3.4 to 6.1 s in
  Chrome, nearly all of it the compile, against 0.25 s for an interpreted hello and 0.9 s
  for an interpreted program reaching `Thread`; a program that starts no thread pays one
  walk of its forms. The image: 42,800,820 -> 43,063,902 B (four builds of the change
  43.06 to 43.30 MB: `CompileFrontend.expand` and what it reaches, +0.6 to 1.2%).
- **What does not run there**, and says why: a program that starts threads and sends an
  HTTP request (a Preview 1 module has no transport: the compile's own refusal, under a
  line saying why the program was compiled; `sendAsync` reaches a pool, so the doc's client
  example is one -- `.todo/k22`), a server (the interpreter's `IOException`).
- **Standard error is on the page** (`PlaygroundRepl.page()`, which `RontoPlayground.shown`
  makes `System.err` for the call): an unchecked warning shows before the output it
  precedes and the report of what `main` left uncaught is the cell's text. Until k10 both
  went to the browser's console and the server example's cell showed nothing.
- **Pinned by** `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram`
  (the hand-off, the session's refusal, the other picks' threads; a JVM has threads, so it
  cannot run the image's route) and `web/playground-check.js` (the image from node: the
  pool example, a sleep and a join with standard input, an uncaught exception, the
  refusals, a compiled macro; `pages.yaml` runs it before publishing, `./mvnw test` does
  not). How the pages were driven: `.todo/artefacts/k10-playground-a-java-program-with-threads-freezes-the-browser/`.

## java.util.concurrent (2026-11-17, j83)

**Invariant: everything of `java.util.concurrent` is Java source over the monitors of
"Threads" -- no primitive, no backend change, and since k00 no branch for a target: a
thread waits on wasm as it does on a host (until then a member that would wait for a
thread suspended under it asked `Support.oneStack()` and did what one stack could).**

- **The classes a JDK synchronizes hold their monitor.** `Vector`, `Stack` and `Hashtable`
  declare each member `synchronized` over the unsynchronized body above them (`super.m()`;
  `Hashtable` reaches `Map`'s defaults through `AbstractMap`: `Map.super.m()` there is no
  Java -- javac refuses the qualifier of an interface the superclass already has, found by
  `theLibrarysSourceIsJavaAJdkCompiles`). `Collections.synchronizedList` / `Set` /
  `Collection` / `Map` are `java.util.Synchronized`'s wrappers, the lock the wrapper or,
  for a map's views, the map. `StringBuffer` stays `StringBuilder`'s representation: its
  intrinsic members are emitted through `Context.holding` (`JavaIntrinsics.guarded`), which
  binds the receiver and the arguments and holds the receiver's monitor around the same
  form -- the member's own form in a program that holds no monitor, so a thread-free program
  pays nothing (`Sb`, a thread-free program over the three: +932 B of class and +227 B of
  wasm, the overrides alone). Four threads adding 2,000 elements each lost elements and
  raised `ArrayIndexOutOfBoundsException` before (interpreter and JVM).
- **What is there**: `TimeUnit`; `Lock` / `ReentrantLock` / `Condition`; `CountDownLatch`;
  `Semaphore`; `BlockingQueue` with `ArrayBlockingQueue` and `LinkedBlockingQueue` (one
  class, `Bounded`, over an `ArrayDeque`), `ConcurrentLinkedQueue`; `ConcurrentMap` /
  `ConcurrentHashMap`; `CopyOnWriteArrayList`; `Executor` / `ExecutorService` / `Executors`
  / `ThreadPoolExecutor` (made by `Executors` alone until j89) / `Callable` / `Future` / `FutureTask`
  / `ThreadFactory`; `CompletableFuture` / `CompletionStage`; the five exceptions. Beside
  them `Thread.State` and `getState`, the uncaught-exception handlers,
  `InheritableThreadLocal`, `Thread.Builder.factory()`, `java.lang.Void`.
- **Added by j89** (2026-11-18, each member's behaviour a JDK 25's answer to a probe, the
  probes in `.todo/artefacts/j89-java-front-end-what-the-concurrent-slice-left/probes/`):
  `ReadWriteLock` / `ReentrantReadWriteLock`; `CyclicBarrier`, `Exchanger`;
  `SynchronousQueue`, `BlockingDeque` / `LinkedBlockingDeque`, `ConcurrentLinkedDeque`,
  `CopyOnWriteArraySet`; `AtomicIntegerArray` / `AtomicLongArray` / `AtomicReferenceArray`,
  `LongAdder`; `ScheduledExecutorService` / `ScheduledThreadPoolExecutor` /
  `ScheduledFuture` / `Delayed`, `java.util.Timer` / `TimerTask`; a program's own
  `ThreadPoolExecutor` with its queue, its four policies and `RejectedExecutionHandler`;
  `ForkJoinPool` / `ForkJoinTask` / `RecursiveTask` / `RecursiveAction`; `invokeAny`;
  `Future.State` with `state` / `resultNow` / `exceptionNow`; `CompletableFuture`'s
  `thenAcceptBoth`, `runAfterBoth`, `applyToEither`, `acceptEither`, `runAfterEither`,
  `thenCombineAsync`, `orTimeout`, `completeOnTimeout`, `completeAsync`,
  `delayedExecutor`, `copy`; `toString` of a lock, a latch, a semaphore, a pool and a
  future.
- **Added by k01** (2026-11-18, learned the same way; probes, the script running one on
  four backends and the sizes in
  `.todo/artefacts/k01-java-front-end-what-the-timers-and-concurrent-classes-slice-left/`):
  `StampedLock`; `Phaser`; `TransferQueue` / `LinkedTransferQueue`; `CountedCompleter`;
  `LongAccumulator`, `DoubleAdder`,
  `DoubleAccumulator`; a `ThreadPoolExecutor`'s `beforeExecute` / `afterExecute` /
  `terminated`, its setters, `prestartCoreThread` / `prestartAllCoreThreads`, `purge`,
  `isTerminating`, on a scheduled pool too; the wrappers `Executors` answers for the
  pools of one thread and `unconfigurable..`; `CompletableFuture`'s `exceptionallyAsync`,
  `exceptionallyCompose` / `..Async`, `obtrudeValue` / `obtrudeException`,
  `minimalCompletionStage`, `completedStage` / `failedStage`, `newIncompleteFuture`,
  `defaultExecutor`, and every `..Async` member on `CompletionStage`.
- **Added by k08** (2026-11-19, learned the same way; probes, `run4.sh` and the sizes
  in `.todo/artefacts/k08-java-front-end-what-the-stamped-lock-phaser-and-pool-hooks-slice-left/`):
  the three field updaters, `ExecutorService.invokeAll` with a limit,
  `Thread.join(Duration)`; the atomics, the adders and the accumulators are `Number`s
  ("The decisions"). Still not there: `.todo/k21`.
- **A field updater holds the NAME of its field** (k08). `newUpdater(C.class, "f")`
  (a reference's `(C.class, V.class, "f")`) is lowered where it is called
  (`JavaLowering.fieldUpdater`, taken in `invocation` before the arguments are lowered:
  the literals are read as written and make no class object): the field is found among the fields the class itself
  declares, and the call is `(|..FieldUpdater.of(Object)| '|C.f|)`, the accessor's
  symbol. Not a number counted where the calls are lowered: a session's buffer does
  not lower the statements of the buffers before it, so an updater made by one buffer
  found no arm in the next (`NullPointerException`; the session test's `up`). For the
  same reason a session's functions have an arm for every volatile instance field of
  the program an updater can be made for (`updatedFields`), a program's for the fields
  it makes one for. The updater's five abstract members are `native`
  calls of three functions of the program, `%field-get` / `%field-set` / `%field-swap`
  (`fieldFunction`: a `case` over the names; an object that is no instance of the
  field's class, `null` too, and a value that is none of a reference field's erased type
  are a `ClassCastException` with no message, a JDK's answer to a raw type), and every
  other member is a loop of swaps in Java. **A swap is ONE volatile access**
  (`ordered`: under `%java-volatile` in a program holding monitors), so it is atomic
  beside a plain write of the field -- an updater synchronizing on itself or on the
  object would lose a write made between its read and its store. Rejected: the field's
  accessors as lambdas held by the updater (k01's lead): a `funcall` of a value the
  resumable analysis knows no interface of reaches every lambda of the program
  (`JavaResumable`, "FUNCALL"), which is what made `Timetable`'s end as a `Runnable`
  cost 100 KB. A swap compares as `==` does (a `String` field turns
  `identicalStrings` on; two boxes of one value are one object, so
  `compareAndSet(o, 1000, 5)` on an `Integer` field succeeds where a JDK's two boxes
  differ: `Updaters3.java`). **What `newUpdater` refuses is a JDK 25's, in its order,
  thrown where the call runs** (`java.util.concurrent.atomic.Fields`; 49 calls asked):
  no field of the name in the class itself, an inherited one included --
  `RuntimeException` holding a `NoSuchFieldException`; then, of an `int` / `long`
  updater, another type (`Must be integer type` / `Must be long type`), of a reference
  updater a field whose erased type is not the class given (`ClassCastException`, a
  class above or under it too); then not `volatile` (`Must be volatile type`: a final
  field, an interface's constant); then `static` (`IllegalArgumentException`, no
  message). Refused by name when the program is lowered, where a JDK throws when it
  runs: a name that is no constant, a call with anything but class literals, a field
  the calling class cannot read (`checkAccess`; a JDK's `IllegalAccessException` names
  the modifiers). The classes are named as a JDK's `toString()` shows them
  (`AtomicIntegerFieldUpdater$AtomicIntegerFieldUpdaterImpl`,
  `AtomicLongFieldUpdater$CASUpdater`). Sizes: `CountField` (a volatile field under an
  updater, four threads) 131,502 B of class / 115,737 B of wasm; a program with no
  updater holds none of it.
- **`invokeAll` with a limit hands a task over while time is left** (k08, asked of a
  JDK): every future is made first (a `null` task refuses before any runs), a limit of
  zero or less runs nothing, the futures are waited for in order with what is left, and
  whatever is still open when the wait ends -- the limit, an interrupt -- is cancelled
  with its thread interrupted, so every future answered is done. `Thread.join(Duration)`
  answers whether the thread ended: a duration of zero or less and a thread that ended
  wait for nothing, so an interrupt is left for the next wait (asked: `false true` and
  `true true`); a thread not started is `IllegalThreadStateException: Thread not
  started`, after the `NullPointerException` of no duration.
- **A `StampedLock`'s stamps are a JDK 25's numbers, read from its answers** (k01): one
  monitor holding a sequence that starts at 256 and moves by 128 when the write lock is
  taken and again when it is given back (384 a first write stamp, 512 the next optimistic
  one), and a count of readers; a reader's stamp is the sequence plus the readers there
  were, 126 at most (the 127th reader on is told 382); a stamp is good while its upper
  bits are the lock's sequence, so `validate` of a read stamp holds until a write. What
  the probes also settled: a write stamp converts only when it is the sequence itself,
  a read stamp to a write lock only when the count is one, every interruptible member
  throws for a thread interrupted beforehand though the lock is free, the views' names.
  Not a JDK's: no thread is preferred, so readers arriving for ever keep a writer out.
- **A `Phaser`'s tree is counted under its root's monitor** (k01): the phase is the
  root's, a tier's counts are those of the root's phase it last saw (`current`), a tier
  with parties is one party of the phaser above it and arrives there when its last party
  does. `onAdvance` is the root's alone and runs with the monitor given up on the thread
  whose arrival ended the phase (`advancing`; a party registering meanwhile waits).
  Measured against a JDK: `arriveAndAwaitAdvance` answers the thread whose own arrival
  ended the phase the NEXT phase's number even when the tree ended with it (3, any other
  thread the negative number) -- which thread that is moves with the load, so the corpus
  masks the bit there and asks it of a phaser of one party; `getPhase()` of an ended
  tree is the next phase with the sign bit, of a forced end the current one. Not asked
  of a JDK, the documentation's reading: a party registering when every other has
  arrived waits for the next phase.
- **A pool's hooks cost only a program that declares one** (k01). `afterExecute` is
  handed what a task threw: a catch of every exception around each task, and with it the
  run time's exceptions as classes. It is in `ThreadPoolExecutor.Hooked`, made by the
  `native` static `hooks()`, whose emitter asks the lowering whether a class under
  `ThreadPoolExecutor` declares `beforeExecute` or `afterExecute`
  (`JavaEmitter.Context.declaresBelow`: the program is closed, and a class found late
  lowers the program again) and answers `nil` when none does. Measured, a fixed pool
  running four tasks: 226,375 B of wasm and 278,314 B of class with no hook, 239,533 and
  317,014 with an empty `afterExecute`. `terminated()` needs no catch and is always
  called, on the thread that finds the pool shut down and its last worker gone, outside
  the monitor; until it answers the pool `isTerminating` (a JDK's order, probed). A
  scheduled pool asks the two hooks in its task's `run` (a future throws nothing) and
  `terminated` from its table (`Timetable.atEnd`), whose end is said after that answers.
  The same k01 probes found two things wrong before: a task waiting in the queue of a
  pool with no core thread never ran (no worker was made for it), and a task that threw
  was not counted as completed.
- **A callback of the library is an interface of its own, never `Runnable`** (k01,
  measured): the analysis of what can suspend reads a call through an interface as any
  class or lambda of it, so `Timetable`'s end as a `Runnable` made `close()` and `drop()`
  reach every task of the program and a scheduled pool running one task went from
  250,061 to 348,553 B of wasm; as `Timetable.End` it is 252,774.
- **A stage is made by `newIncompleteFuture()`** (k01), so a subclass decides what its
  stages are, and a `MinimalStage` is the subclass whose stages are minimal and that
  refuses what only a future answers; `toCompletableFuture()` of one is a new future
  ended as it ends. Asked of a JDK: `copy()` and `minimalCompletionStage()` of a minimal
  stage answer one, an exception taken over is held in a `CompletionException`
  (`failedStage` holds it as given). `defaultExecutor()` answers `Common.pool()`, not the
  object `ForkJoinPool.commonPool()` answers (`deviations.md`).
- **The pools of one thread are behind a wrapper** (k01; `Executors`' three nested
  classes, named as a JDK's since `toString()` shows them): `instanceof
  ThreadPoolExecutor` is false, as on a JDK. Cost, a program submitting one task to
  `newSingleThreadExecutor()`: 238,152 -> 241,674 B of wasm (the fixed pool with no
  wrapper 224,454 -> 226,375: the pool's end and its sizes).
- **A task at its time is `rontolisp.internal.Timetable`**, which a scheduled pool and a
  `Timer` share: the tasks sorted by time under one monitor, a fixed number of threads.
  Where a thread can wait, the thread that had no task longest waits for the soonest
  task's time and the others for their turn (a line of the idle threads, a thread joining
  it where it is made). A JDK's pool of two idle threads gave sequential tasks to its
  threads in turn in every quiet run and gave one thread two in a row under load (the
  corpus's JDK leg, beside the other legs), so no test asks which thread runs a task.
  A task that throws ends its thread and
  the table (a `Timer`'s; a pool's tasks are futures and never throw). A
  `ScheduledThreadPoolExecutor` extends `ThreadPoolExecutor` for the type's sake and
  overrides every member over its table; its tasks are `FutureTask`s (`runAndReset` for
  one that repeats). After `shutdown()` a task waiting for its time still runs and one
  that repeats is cancelled, as a JDK's two policies default.
  **A thread of the table reads the clock ONCE a round** (k14, 2026-11-19): that reading
  says whether the soonest task is due and, when it is not, how long to wait. Until then
  `next()` read it twice -- `due()`, then the pause -- and a task whose time came between
  the two was neither taken nor waited for: a pause of 0 down to -2 ms rounds to
  `wait(0)`, a wait with no limit, and one below that is `wait` of a negative number,
  which throws and ends the thread. Either way the program never ended (`main` in a
  `get()` or a latch's `await()`), on every backend, the library being one source. This
  was the "lost wake-up" that stopped full test runs in
  `a-scheduled-pool-and-a-timer-run-tasks-at-their-times` on the interpreter; nothing was
  wrong in the park, monitor or condition-variable path. Measured on 64 cores with
  `.todo/artefacts/k14-java-threads-a-lost-wake-up-hangs-the-interpreter-and-flaky-thread-cases/run.sh`
  (24 JVMs at once, four copies of the case each, the interpreter; load average 15 to 40
  at the start with a full test run beside it, 94 to 107 at the end): 13 and 26 of 96
  copies left waiting before, a print in `next()` showing 30 pauses of -20 us to -21 ms
  in the second run (13 a `wait(0)`, 17 a negative one); 0 of 384 after (four runs, load
  average 61 to 245). Every other wait with
  a limit in the library tests `left <= 0` on the reading it waits by.
  `JavaSpecE2eTest#aTaskWhoseTimeComesBetweenTwoReadingsOfTheClockRuns` forces it: the
  interpreter's `%nano-time` redefined to answer a millisecond later at every reading, so
  a task 1.5 ms away is not due at one reading and overdue at the next.
- **A pool is a core size, a most size, a queue and a handler** (j89; until then a core
  size and a flag). A task gets a new worker below the core size, else waits in the
  queue, else gets a new worker below the most size, else the handler is asked -- outside
  the pool's monitor. The queue is the program's own object, offered to and polled under
  the pool's monitor, on which the workers wait (so a task the program puts into
  `getQueue()` itself waits for the next `execute`: `deviations.md`); a `SynchronousQueue`
  holds nothing, so a pool over one keeps the tasks handed to idle workers itself.
  `Executors`' pools are the same constructor. The refusals' messages are a JDK 25's
  (`corePoolSize must be non-negative` .., `NullPointerException: workQueue`).
- **What depends on a `CompletableFuture` runs in the order a JDK runs it, measured**
  (j89; it ran in the order added until then, which no test asked): of the future set
  first the dependent added LAST first; of a future one of those sets, right after it,
  the one added FIRST first (`d3 e3a d2 e2a e2b g1 g2 e2c d1 e1a e1b` for three
  dependents, the second with three of its own). `settle` keeps one stack per outermost
  completion: a dependent is `fire(pending)`, and a future set inside one pushes its own
  dependents on `pending` in reverse. A stage of two futures is one dependent added to
  both where the stage is asked for, not to the second where the first is done -- which
  is what puts `q1 all combine2 combine1` in a JDK's order. `orTimeout` and the like run
  on one daemon thread of a scheduled pool named as a JDK's.
- **`ForkJoinPool` is a thin layer over a `ThreadPoolExecutor`** that grows a daemon
  thread whenever none is free; `java.util.concurrent.Common` holds the one every
  program shares, which a `CompletableFuture`'s tasks run on too. One thread runs a task:
  a pool's thread, or -- before any has -- the pool's thread that joins it
  (`ForkJoinTask.run` under the task's monitor), so `left.fork(); right.compute() +
  left.join()` needs no second thread. The pool a thread runs a
  task of is a `ThreadLocal`. Not a JDK's: the parallelism is a number answered (2, and 1
  for the common pool: there is no `Runtime`), no work is stolen, and an exception is
  thrown again as it is where a JDK, when another thread ran the task, throws a new one
  of the same class holding it -- made by reflection, which the subset has none of
  (`deviations.md`).
- **A member that never waits calls nothing that can** (j89, a rule of the library's
  source): `offer(e)`, `poll()`, `tryLock()`, `add` have bodies of their own beside the
  members that wait (`Bounded.insertNow` / `insert`, `SynchronousQueue.offer` / `hand`,
  `ReentrantReadWriteLock.take` / `acquire`), and a callback of the program runs with the
  monitor given up where it can (`CyclicBarrier`'s action). Why: a `synchronized` region
  is held across a suspension as soon as a call in it can REACH a wait, and through the
  interfaces (`Collection.add`, `Queue.poll`) one such member makes every region calling
  the interface one -- sharing a body with the waiting member put 30 regions of the
  corpus's `Handing` in that set and took the functions to lower twice from 18 to 49%
  ("A thread suspended and resumed" below). Pinned by
  `JavaLoweringTest#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`: no region of
  the library is held across a suspension in the corpus's thread programs.
- **A lock is a monitor's protocol, not a monitor**: `ReentrantLock` holds an owner and a
  count under its own monitor, on which a thread waiting for the lock waits; a `Condition`
  queues a ticket per waiting thread under the lock and each thread waits on its own ticket,
  so `signal` wakes the thread that waited longest and a signal between the lock given up
  and the wait is not lost (`locks.Waiting`, over what a lock answers of itself, so a
  write lock's condition is the same class). `ReentrantReadWriteLock` is one monitor: a
  thread asking to read waits while another writes, and while one waits to write unless
  it reads already; `tryLock()` goes ahead of the waiting writers, as a JDK's does.
- **A pool's workers.** The first `core` tasks each get a worker of the next number, as a
  JDK's do; an idle worker waits for a task on the pool's monitor; a worker a task's
  exception ended is replaced by a thread of the next name, started with the next task or
  kept in `resting` until one comes (a JDK does the same: `pool-3-thread-2` in the
  corpus). `CompletableFuture`'s tasks with no executor run on a growing pool of daemons
  named as a JDK's common pool's. Until k00 a worker with no task left ended on wasm and a
  later task started a thread of its name.
  **A worker that ends leaves `workers` in the monitor hold that decides it** (k54,
  2026-11-20: `next` answers `leave(worker)`). Until then it left later, in `ended`, and
  an `execute` between the two counted it, queued the task and woke nobody: the task
  never ran (`main` in a latch's `await`). Measured with a probe of 300 tasks in a row on
  one pool of no core thread, each awaited before the next: 76 stranded on the
  interpreter and 70 on the JVM with a keep-alive of 0, 129 on the interpreter with one
  of a nanosecond (the timed wait's end); 0 on both wasm targets, whose threads take
  turns, and 0 under the JDK. The corpus's
  `locks-latches-queues-and-executors-hand-work-between-threads` runs 200 such tasks on
  two pools.
- **`ConcurrentHashMap` keeps the order a JDK 25 walks it in, derived from outputs alone**
  (rule 2 of the provenance file: no source was read; each rule below is the reading of a
  probe run under the JDK, then checked by differential runs: 80 seeds of 300 random
  operations, the walk printed after every step -- 52 seeds print the JDK's at every step,
  and each of the other 28 first departs at a step where a chain holds nine keys or more in
  a table of 64 or more; 17,396 steps identical. The probes and the runs:
  `.todo/artefacts/j83-java-front-end-what-the-threads-slice-left/`). The index is the
  hash folded with its upper half and masked; a new key goes to the END of its chain
  (`put`, `putIfAbsent`, `computeIfAbsent`, `compute`, `merge` alike; a `HashMap`'s three
  put AHEAD); the table starts at 16, or for
  `new ConcurrentHashMap(n)` -- and for a copy of a map of `n` keys -- at the power of two
  at or above `1 + n / 0.75` (0..70 probed); it doubles when the count REACHES three
  quarters (12, not 13 as a `HashMap`); a doubling keeps the chain's last run of keys going
  the same way as it is and puts each key ahead of that run at the HEAD of its new chain, in
  turn (so `[0, 16, 32, 48]` becomes `[32, 0]` and `[16, 48]`); a walk of eight keys of a
  chain by one of the five members above -- a ninth key added, or the eighth found -- in a
  table shorter than 64 doubles the table until three quarters of it is at least the power
  of two at or above `3 * length + 1` (16 becomes 128); `putAll` into a map that has a table
  first grows it the same way for `size() + m.size()` keys (`1.5 n + 1`), into one that has
  none it does not. Not kept, as a `HashMap`'s: a chain of more than eight keys in a table
  of 64 or more (a JDK puts the tenth key at the head of what is then a tree; its order
  after a removal depends on the tree's shape). Every member is `synchronized` on the map;
  an iterator walks the table there was when it was made, and a doubling makes nodes of its
  own for the keys it moves so that walk stays whole.
- **What a program can tell of a JDK's messages, measured**: a cancelled
  `CompletableFuture`'s `join` / `get` throw a NEW `CancellationException` whose message is
  the member's name and whose cause is the cancelling; a stage's own exception and one taken
  over from the stage before it are held in a `CompletionException`, one given to
  `completeExceptionally` as it is (so `exceptionally` after a `thenApply` reads the
  wrapper); `allOf` fails as the first in the order given that failed; what a handler throws
  is `Exception: <class> thrown from the UncaughtExceptionHandler in thread "<name>"` after
  an empty line.
- **A program that sets no handler holds no catch of every exception**: `Thread.body` calls
  the `Guarding` interface, whose one class is made by the two setters, so the catch of
  `Throwable` -- and with it the run time's exceptions as classes -- is lowered only for a
  program that sets a handler ("Lowered as far as the program reaches"). The same for
  `InheritableThreadLocal`: `Thread`'s constructor calls `Inherited.child()`, whose one class
  that thread local makes.
- **A library enum needs `java.lang.Class`**: the `valueOf` javac writes for an enum calls
  `Enum.valueOf(Class, String)`, so the library's own compile under a JDK needs the member,
  and this front end types every signature of a class it reads. `Class` was a type with no
  value until k30 ("A class as a value"), since when `Enum.valueOf(Class, String)` walks the
  class's constants.
- **A compact source file brings the three packages** (`LIBRARY_PACKAGES`): the atomics were
  not brought before.
- **Cost to a program that starts threads** (the members `Thread` gained; class / wasm
  bytes): a counter under four threads 172,141 / 99,263 -> 174,715 / 100,731;
  `examples/java/shop` 414,747 / 301,780 -> 418,004 / 303,382. `Fib` and `Sieve` are
  byte-identical.

### A thread suspended and resumed: the two leads measured (2026-11-17, j83)

The second is made (k00, 2026-11-18: "Resumable bodies" below); the first waits for
wasmtime: re-measure the three conditions, and the component threads' one, on each
wasmtime the project moves to. j89 made the first step of the second lead: "The regions a
suspended thread can hold" below.

- **Wasm stack switching is in wasmtime 49.0.0 and cannot be used.** It is off by default
  (`-W stack-switching=y`), only on x86-64 Linux and macOS (`Config`'s check: any other
  target, aarch64 and Pulley included, is unsupported -- the native outputs target
  `macos-aarch64`), and turns the compiler's inlining off. A hand-written module (`cont.new`
  / `resume` / `suspend` over a GC struct held on the suspended stack, 20 frames deep) runs
  1,000,000 round trips in 0.19 to 0.25 s and an exception thrown in a continuation reaches
  the `try_table` around its `resume`; but a COLLECTION WHILE A CONTINUATION IS SUSPENDED
  ABORTS THE HOST (`expected one more VMStackLimits than continuations`,
  `traphandlers/backtrace.rs:340`, the DRC and the copying collector alike; the null
  collector runs it). So j76's "not in wasmtime 49" was wrong in the letter and right in the
  effect. Re-measure on each wasmtime the project moves to: the three conditions are the
  flag's default, aarch64, a collection with a suspended stack.
- **The component model's threads are in wasmtime 49.0.0 and pass the collection, not the
  default (k07, 2026-11-19; x86-64).** `-W component-model-threading=y` gives a component
  `thread.new-indirect`, `thread.suspend-then-resume` (this toolchain's name for the switch;
  `suspend-then-promote` trapped `cannot block a synchronous task before returning`),
  `thread.resume-later`, `thread.index`: each thread a fiber of the host. A hand-written
  component (`.todo/artefacts/k07-java-front-end-what-the-suspended-threads-slice-left/cm-threading/threads.wat`: the stack-switching
  probe's shape, a GC struct held 20 frames deep on the suspended thread, a 64-element
  array made between two switches) runs 1,000,000 round trips in 1.1 to 1.3 s under the
  DRC, the copying and the null collector with the right sum: A COLLECTION WHILE A THREAD
  IS SUSPENDED HOLDS. 0.55 us a switch -- a `cont` switch was 0.1 us, the futures'
  hand-over is 2.2 us. But the flag is off by default (without it the component is refused
  at load: `thread.new-indirect requires the component model threading feature`), so a
  `--component` output could not be run by `wasmtime run` / `serve` as it is; aarch64 was
  not measured (no machine; the fibers are wasmtime's async stacks, which it has there);
  and a `--native` output is a core module with no component built-in, so it would keep
  the resumable lowering whatever a component does. Not made. The condition to re-measure
  on each wasmtime is the flag's default alone; then a component's thread can be a host
  thread, its scheduler a switch, and no function of a component is lowered twice.
- **Resumable bodies: how much of a program would be lowered twice.** The functions of the
  program and the library (`--dump-ir`, direct calls and dispatchers by name; a call through
  a function value is not followed) that can reach a point where a thread is suspended:

  | program | functions | reach `wait` / `sleep` / `yield` | or a monitor's entry |
  |---|---|---|---|
  | a bounded buffer (two methods, two threads) | 59 | 6 | 15 |
  | `examples/java/shop` | 254 | 4 | 17 |
  | the corpus's `Monitors` | 309 | 7 | 139 |
  | the corpus's `Handing` (locks, queues, executors) | 334 | 53 | 137 |
  | the corpus's `Sharing` (`ConcurrentHashMap`, futures) | 484 | 40 | 229 |

  A monitor's entry has to count: a thread suspended in `wait` still holds every OTHER
  monitor it entered, so another thread's `synchronized` can find one held. With it, a
  program over the concurrent library has 40 to 47% of its functions resumable; without,
  2 to 16%. What decides it is whether a `synchronized` region can be held across a
  suspension, which the closed program can answer per region (no call in it reaches a
  suspension -- every atomic, every `ConcurrentHashMap` member).

### The regions a suspended thread can hold (2026-11-18, j89)

**The analysis brings the functions to lower twice back to the ones that reach a wait, a
sleep or a yield, in six programs of seven; the seventh is decided by how well a function
value is typed, which the IR does not tell and the lowering does.** The lowering was
therefore worth making, and k00 made it ("Resumable bodies" below).

- **What is computed** (`held/held.py` over `--dump-ir`, a fixed point over the call
  graph): a region is HELD ACROSS A SUSPENSION when its body reaches a sleep, a yield, an
  entry that may block, or a wait on anything but the region's own lock (a wait gives its
  own monitor up: `Thread.await` is "waits on its first argument", handed up the calls as
  long as the argument is a parameter); an entry MAY BLOCK when its lock may be the lock
  of a held region (`this` of related classes, the same static lock, a `new Object()`
  against the like, anything else against everything); a function is resumable when it
  reaches a suspension or an entry that may block. A lazy class's initialization needs no
  rule of its own: it is a function, and the wait for it is inside.
- **Measured** (2026-11-18, the corpus's standalone programs as j89 left them and
  `examples/java/shop`; functions written in Java, the library's included):

  | program | functions | reach a wait, a sleep, a yield | or a monitor's entry | resumable, function values not followed .. followed by arity | regions | held |
  |---|---|---|---|---|---|---|
  | a bounded buffer | 62 | 6 | 15 | 6 .. 9 | 7 | 0 |
  | `examples/java/shop` | 271 | 4 | 25 | 4 .. 7 | 9 | 0 |
  | `Monitors` | 325 | 7 | 142 | 7 .. 15 | 59 | 0 |
  | `Waits` | 79 | 8 | 19 | 8 .. 11 | 18 | 0 |
  | `Handing` (locks, barriers, queues, pools) | 582 | 110 | 308 | 104 .. 106 | 120 | 0 |
  | `Timed` (a scheduled pool, a `Timer`) | 278 | 45 | 123 | 10 .. 17 | 59 | 0 |
  | `Sharing` (`ConcurrentHashMap`, futures, fork/join) | 853 | 121 | 471 | 42 .. 425 | 168 | 0 .. 55 |

  The two ends are the unsound and the sound reading of a call through a function value:
  not followed, and reaching every lambda and `#'function` of its arity. Where they meet,
  the answer is exact: 1.5 to 18% of a program's functions, against 9 to 55% when every
  monitor's entry counts. Of a program's lambdas 0 to 41% are resumable (`Handing` 34 of
  82).
- **`Sharing` is not answered by the IR.** Its 425 come from one place: a
  `CompletableFuture`'s dependents are lambdas bound to variables and called through a
  local (`next.fire(stack)`), a field (`this.threads.make(..)`) -- in the IR a `FUNCALL`
  of an unknown operand, which then reaches every lambda of one parameter, a thread
  factory's among them; from there `Timetable.arm`, the pools and the map's `compute`
  hold their monitors across "a suspension" and 127 entries may block. The lowering
  knows the interface of every lambda and of every such call (the IR only where a lambda
  is written as an argument: the script's `typed` and `flow` readings), so the analysis
  belongs in `JavaLowering`, beside `Effects`; whether interface precision is enough for
  `Sharing`, or the lambdas handed to each parameter are needed (`flow`: 34 of 492 on
  the program as j83 left it), was k00's first measurement: they are needed ("Resumable
  bodies").
- **The component runs a suspended body today** (the second lead tried by hand,
  `async/`): a bounded buffer written as two `async-defun` bodies, an `unwind-protect`
  and a `handler-case` across the awaits and an error signalled after a resume, prints
  what the interpreter and the JVM print (33 KB). What a resumable call costs when it
  does not suspend, 20,000,000 calls of `(+ n 1)` under an `unwind-protect`, wasmtime
  49.0.0: a plain call 0.42 s / 0.35 s (Preview 1 / component), an `async-defun` awaited
  1.11 s / 2.13 s -- 35 ns a call on Preview 1 (a settled future made and read) and 90 ns
  on the component (the frame). Preview 1 runs a body to its end at the call
  ([async-await.md](async-await.md)): it has no state machines, and a thread's wait
  needs a future the PROGRAM settles, which neither wasm target has (`make-stream` is
  refused there) -- both corrected by k00: the component's `%future-new` is such a
  future, and Preview 1 runs the component's machines for a module naming it.
  `(await (wait-for 0))` does not stand in for a yield: on the component
  a timer of 0 ms is settled when it is made, the await never suspends, and a loop
  polling with it never lets another body run -- the interpreter and the JVM end, the
  component does not (`async/spin2.lisp`, `.todo/k02`).


### Resumable bodies (2026-11-18, k00)

**Invariant: on wasm a program that starts threads is lowered with every function a thread
can be suspended in as an `async-defun` and every call of one as an `await`
(`java/JavaResumable`, run over the lowered forms where `SourceLanguage` reads for a wasm
target and `Thread.start()` or, since k11, the HTTP server is reached); a thread that cannot
go on awaits a future of its own, and the thread the program started on settles the futures
of the threads that can, the one waiting longest first (`java.lisp`'s `#+rontolisp-wasm` "threads").** The interpreter
and the JVM keep host threads and the forms they had.

- **What it took of the backends: one gate and one bug.** The component had the state
  machines and a future a program settles (`%future-new` / `%future-settle`, which the
  todo had overlooked). Preview 1 needed no machine of its own: `WasmAsyncEmit`'s frames
  run there as they are once `asyncMode` is on, which it now is for a module naming
  `%future-new` ([async-await.md](async-await.md), "Preview 1"). The bug: a nested
  `async-lambda`'s parameter was a free variable of the body around it
  (`FreeVarAnalyzer`).
- **The analysis** (a fixed point over the calls; `JavaResumable.Census` is what a test
  and these notes read). A function is resumable when it reaches a SUSPENSION: a sleep, a
  yield, a wait, the entry of a monitor that may block, a loop's turn. A region is HELD
  ACROSS A SUSPENSION as j89 defined it (a wait on the region's own lock gives it up,
  handed up the calls while the object is a parameter). An entry MAY BLOCK when its lock
  may be a held region's (`Lock.mayBe`: the static types related, a class's own lock that
  class's alone, a `final` field made by `new C()` an object of exactly `C` -- so the
  `new Object()` a program sleeps under is no library class's `this`). What the forms do
  not say the lowering records by identity (`JavaResumable.Facts`): a lambda's interface,
  the interface a `funcall` goes through, a monitor's lock, the dispatchers, the loops of
  an enhanced `for`.
- **A function value is followed to the lambdas handed to the parameter it is** (the todo's
  first measurement). With a `funcall` reaching every lambda of its interface `Sharing`
  had 395 resumable functions of 853, 49 regions held and 114 entries that may block: one
  resumable `Function` anywhere made `ConcurrentHashMap.compute`'s region held, and from
  there every member of the map and every `equals`. So each parameter keeps the lambdas
  the calls hand it (`Flow`: a lambda written as the argument, a parameter handed on, a
  parameter of the function a lambda is written in; anything else is any lambda of the
  interface, as is a parameter assigned), and a dispatcher called on such a receiver is
  read at the call: its arms, and those lambdas. `Sharing` is then 103 of 848, no region
  held.
- **A loop's turn.** Threads take turns only where they suspend, so a loop only another
  thread ends would turn for ever (2 of the 16 programs). A loop outside every region of
  its function whose test reaches a synchronization action (a `volatile` access, a
  monitor's entry, the interrupt's test) through any call, or whose body reaches one
  through calls by name, counts down `%java-slice` and once in 1,024 turns lets a thread
  that can go on do so (`%java-suspending-turn`) -- unless the thread holds a monitor
  (carrier slot 5), so a turn holds no region and makes none held. The test of an
  enhanced `for` is exempt: through `Iterator.hasNext()`'s dispatcher it reaches every
  iterator a program makes, and gave every walk of a collection a turn (`Handing` 153
  resumable functions with it, 113 without). What still keeps its turn is in
  `deviations.md`: a loop reading only plain fields or polling through an interface in
  its body, a loop spinning inside a monitor.
- **The forms.** An `await` compiles only where the operand stack is empty and the
  backend hoists only calls it knows strict, so `JavaResumable` binds what a call
  evaluates ahead of its last suspension (`operands` / `value`; a literal and a local
  nothing later assigns stay). A `handler-case` clause and an `unwind-protect` cleanup
  cannot hold an await (the backend refuses both): a clause that suspends only records
  which one took the condition and runs after the form; a cleanup that suspends runs
  after its form left by a block of its own, what it threw kept and thrown again, an
  exit of a block around it (a `return`, `break`, `continue` through a `finally`) kept
  and taken again. A function whose suspensions are all tail calls stays a `defun`
  answering the callee's future (a dispatcher, `Thread.join()`): an `await` of what is no
  future is the value. A function the run time calls (a `#'function`, a lambda the
  lowering wrote) is called where nothing awaits, so a resumable one is wrapped in
  `%java-now`: its value when it ended at once, else a refusal by name -- a
  `synchronized toString()` under `String.format` works while no thread holds the monitor.
- **The scheduler** is Lisp over three lists: the line of the threads that can go on, the
  threads waiting for a time (soonest first), and per monitor the threads waiting to enter
  it (slot 6). A carrier is `#(thread turn queued interrupted monitor holds)`. `start()`
  parks the new thread in the line, so it returns before the thread runs. The program's
  end (`%java-thread-finish`) gives turns while a thread that is no daemon lives; with
  none that can go on it sleeps until the soonest time, and with no time either the
  program is stuck: `%java-stuck`, a message and the status 1, where a JDK waits for ever.
  An entry or a class the analysis called free that a suspended thread holds is
  `%java-unforeseen`, never gone on from.
- **Measured** (2026-11-18, wasmtime 49.0.0, Preview 1 bytes; one stack is the tree before
  k00):

  | program | functions | resumable | lambdas resumable | regions held | bytes, one stack | resumable | time, one stack | resumable |
  |---|---|---|---|---|---|---|---|---|
  | a bounded buffer | 62 | 9 | 3 of 6 | 0 | 162,085 (refused) | 177,463 | - | - |
  | `examples/java/shop` | 271 | 4 | 1 of 17 | 0 | 310,987 | 326,075 | 26 ms | 25 ms |
  | `Monitors` | 325 | 11 | 6 of 19 | 0 | 265,031 | 310,716 | | |
  | `Waits` | 79 | 11 | 7 of 14 | 0 | 188,733 | 226,872 | | |
  | `Handing` | 578 | 113 | 36 of 85 | 0 | 417,378 | 711,585 | 114 ms | 140 ms |
  | `Timed` | 289 | 19 | 4 of 26 | 0 | 305,607 | 343,945 | | |
  | `Sharing` | 848 | 103 | 35 of 126 | 0 | 676,386 | 898,379 | 157 ms | 168 ms |
  | `Turns` (the new case) | 333 | 33 | 16 of 23 | 1 | - | 456,170 | | |

  A program that starts no thread is byte-identical (`Fib` 133,389, a program printing
  stack traces 58,137, on both targets); one that reaches `Thread` and starts none grows
  1.4 KB (the monitors' seventh slot and count). `Handing`'s 71% is one function: its
  `main`, 85 KB as a resume body with an await in most statements. A resumable call that
  does not suspend costs 88 ns on both targets (20,000,000 calls of `(+ n 1)` under an
  `unwind-protect`: 0.38 s plain, 2.15 s as an `async-defun` awaited; it was 33 ns on
  Preview 1 while a body ran to its end at the call). A hand-over between two threads by
  `wait` and `notify` is 2.2 us (400,000 in 0.9 s; a JDK 25 takes 4.9 s with host
  threads). A synchronized counter under four threads, 8,000,000 increments: 0.95 s on one
  stack, 2.45 s now -- the loop takes turns, so it is a resume body, where the backend
  drops its integer fusions; k07 wrote the loop as a function of its own (below): 1.43 to
  1.65 s.
- **Not made**: a Java file a Common Lisp program loads that starts threads and has no
  `main` is refused on wasm (nothing would give the turns).

### What k07 measured and made of the resumable lowering (2026-11-19)

- **A loop whose one suspension is its turn is a function of its own** (`JavaResumable.turns`).
  Measured first, on the lowered forms of `bench/Counter` edited by hand and compiled again
  (the Probe's forms are a Lisp program: `rontolisp forms.lisp -o x.wasm` gives the bytes of
  the Java file's own output; wasmtime 49.0.0, precompiled, 8,000,000 synchronized
  increments under four threads): the loop in the resume body 2.50 s; a plain `lambda`
  sharing the loop's assigned local 1.67 s; the local copied in and out 1.57 s; a named
  function handed and answering it 1.55 s; and a plain lambda that never counts a turn --
  the bound -- 1.65 s. So what the resume body cost the loop was 0.9 s (110 ns a round: a
  body a thread is resumed in keeps no number unboxed, and the `int` mask was four generic
  operations then), every plain shape reaches the bound, and the backend's way (keeping the
  fusions between two suspend sites: every `ctx.asyncResume != null` in the expression
  compilers) could give this loop no more than the bound. Made: the simplest shape,
  `(let ((run (lambda () (block left (while test body.. (if (< (setq slice (- slice 1)) 0)
  (return-from left t))) nil)))) (while (funcall run) (await (%java-suspending-turn))))` for
  a turning `while` with no other suspension in it (the innermost ones: a loop holding a
  turning loop holds a suspension). An exit of a block around the loop -- `break` of an
  outer label, `return` -- is what `leaving` already wrote for a cleanup: a number and a
  value, taken by a `cond` once the function returned, so no exit crosses the lambda. The
  turn is counted after a round instead of before the test. Now: Counter 1.43 to 1.65 s on
  both targets (Preview 1 109,410 -> 111,660 B), the hand-over (`Bounce`) 0.87 s as it was.
  What it costs is bytes: a plain function gets the speed trades a resume body does not,
  1.8 KB a loop (`Monitors`: 8 lambdas +14.3 KB, its resume bodies -2.7 KB). The corpus,
  Preview 1 / component bytes, before -> after all of k07: `Monitors` 311,157 -> 323,693 /
  317,391 -> 335,044; `Waits` 240,199 -> 241,694 / 246,074 -> 253,133; `Turns` (with its new
  case) 470,128 -> 471,407 / 478,050 -> 485,065; `Handing` 953,828 -> 961,876 / 963,112 ->
  977,065; `Sharing` 1,040,625 -> 1,055,702 / 1,047,213 -> 1,067,642; `Timed` 398,229 ->
  401,853 / 404,456 -> 413,674. What is left of the counter's 1.5 s is the monitor (above).
- **What a suspend site costs: 250 B and 12 B for each local alive across it** (measured,
  a Lisp `async-defun` of n awaits in sequence and l locals read after the last: 1 await
  329 B, 11 awaits 2,839 / 4,615 / 6,405 B at 0 / 10 / 20 locals, 21 awaits 5,357 / 8,353 /
  11,363 B). So a body's bytes grow with awaits x locals: `Handing`'s `main`, 82 awaits and
  some 74 locals alive at each, is 93,005 B, of which about 73 KB are the spills (its
  module 961,876 B, 377,598 B of it 154 resume bodies). Not made, and no lowering of the
  front end's can: the locals of a Java method are what they are. The way is the backend's
  -- a local alive across an await living in the frame, read and written there, with no
  spill and no restore -- and is `.todo/k19` with these numbers.
- **Loops that keep their turn: decided, kept as stated in `deviations.md`.** The rule
  ("a loop's turn" above) is what makes a function resumable, and widening it is paid in
  functions lowered twice, not in the loop: every dispatcher a turn made `Handing` 153
  resumable functions against 113 (k00). With the loop a plain function a turn costs a
  loop 2 us in 1,024 rounds, so the price of a wider rule is that table's alone; no rule
  between the two was found that tells a loop reading a plain field another thread writes
  from one that does not. A daemon turning for ever no longer keeps a component from
  ending or from serving (below).
- **What the analysis reads coarsely, and what is refused: decided, kept.** `Flow.ANY` for
  a lambda bound to a local or a field, a lock known by its static type: the corpus holds
  no region and no entry that may block but for the program that sleeps in a monitor
  (`#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`), so precision would buy
  nothing measured there. Threads that all wait end the program (a JDK waits for ever: no
  test can hold on that, and a program is told why); `%java-now`'s refusal needs a wait
  inside the run time's own printer; a Java file with no `main` under a Common Lisp
  program has no entry to give the turns.

### The host's events give the turns under a component (2026-11-19, k07)

**Invariant: a component never waits in the host while a thread of the program could go
on, and never loops over its threads to pass the time: a thread waiting for a time is
woken by a timer of the host's, one waiting for a reply by the reply, a served request's
by the request, and each gives the threads their turns where the host delivers it
(`%java-thread-pump`). Under `wasmtime serve` the task of a request that waits is
SUSPENDED, so the host enters the instance for other requests meanwhile.** Preview 1 and
`--native` keep k00's loop (`%java-thread-finish` / `-run` passing the time by `sleep`
and taking a server's requests in steps): Preview 1 has no host future, and the runner's
are not askable (`.todo/k18`).

- **Why it had to change** (measured before, wasmtime 49.0.0): a request whose handler
  slept 2 s held its instance in a host call, and the next request the host handed that
  instance was entered after it; a handler waiting for what another request brings was
  `%java-stuck`; a thread waiting for a reply blocked in `_sched_loop` with no thread
  going on, a thread asleep included. A plain Lisp handler that AWAITS a timer was
  already entered at once by a second request (`slow 2 now 3`): the callback driver
  suspends a task whose root future is pending. So the scheduler's own blocking was the
  whole of it.
- **The pump** runs the ready threads until none can go on, with no current task
  (`%task-swap nil`, restored after), so every frame a thread makes belongs to none and is
  resumed wherever its future is settled. It is called by whoever the host woke: the
  program's start (`%java-thread-finish`), a request's task (`%java-http-held`), a timer
  (`%java-thread-alarm`), a reply (`%java-http-reading`). A pump inside a pump is
  nothing (`%java-pumping`): an arm whose future was settled at once ends in one.
- **A HOLDER is who the host delivers to**: the program's start (`%java-top`) or the
  task of a request, `#(bell replies ended condition responder queue writer task gone)`.
  A waitable joins the set of the task current when it is made, and a task that EXITS
  loses what its set still holds (measured: an unawaited `(wait-for 1500)` body under a
  handler that had answered never printed). So what the program asks of the host is
  counted on the holder it was asked under (`%java-host`, found by the task the pump
  swapped out), and a request's task stays in hand -- its driver awaiting its `bell` --
  until its thread ended AND it holds nothing a thread waits for (`%java-carrying`): a
  reply in flight (slot 1), the one armed timer (`%java-armed`, `(deadline . holder)`).
  Rejected: the same in the runtime (`_task_finish` staying WAIT while the registry has a
  waitable of its set). It would hold every served Lisp program's task for each losing
  timer of a read deadline (`sockets.lisp`'s `%future-race`), a change of blast radius no
  measurement asked for.
- **Timers**: `%java-pausing` is `%java-thread-arm` there. After a pump one timer is
  armed for the soonest deadline, unless one no later is armed with a holder still
  delivered to; a timer that fires pumps. A stale one (its thread went on) fires into a
  pump with nothing to do; when no thread waits for a time any more the armed one is
  forgotten and its holder told, so a `await(1, HOURS)` that was released holds no task.
  The program's start under `serve` returns where no thread can go on: its holder is
  `gone`, and what it armed is armed again by the first request.
- **Replies**: in a program whose threads take turns `JavaResumable.rewrite` renames
  `%java-http-exchange` (the lowered `start0` / `timed0`, `(vector future)` everywhere
  else) to `%java-http-launched`, which under a component starts READING the reply at the
  send -- `%java-http-awaited`, the head and each chunk awaited -- into the exchange
  `#(future reply waiting)`; `%java-suspending-http-reply` parks until it is in. At the
  send, not at the wait: `sendAsync` sends on one thread and waits on another, and the
  send's subtask is in the set of the task current THEN.
- **The end of a program** (`wasmtime run`): `%java-thread-finish` pumps and, while a
  thread that is no daemon lives and the start holds something, forces its bell
  (`_sched_loop` dispatches the timer or the reply, whose arm pumps); nothing held is
  `%java-stuck`. A pump on the start's holder ends when only daemons are left, wherever
  it was called from: a daemon turning for ever was resumed for ever by the alarm that
  woke `main` (the corpus's `Turns` never ended, 12 minutes of CPU, before that).
- **Threads that go on turning** (a daemon spinning, `while (!flag) Thread.yield()`):
  `%java-thread-others` counts the host as one while a server runs or a reply is in
  flight, so such a thread joins the line instead of going on; a pump that has run
  64 turns and 10 ms then stops with a 1 ms timer on its holder (`%java-thread-nap`, one
  at a time: one a request leaked a task for each, and the latency grew by 50 ms a
  request). Measured: a served program with a spinning daemon answers in 40 to 120 ms.
- **A request under `serve`**: `%java-http-request thunk responder` (the lowering hands the
  transport's responder to the run time; the request's threads get the HOLDER for one)
  queues the request's thread, and answers the future of `%java-http-held`, a frame of the
  request's OWN task: pump, then await the bell until the thread ended and nothing is
  held. The transport awaits it: a delayed response's function may answer a future
  (`.kb/http-server.md`), and `JavaResumable` marks the function holding
  `%java-http-request` awaited, or `%java-now` would refuse the pending future.
- **A response leaves through its own task.** `task.return` is the host's notion of the
  current task, and a request's thread takes its turns on whichever task the host woke.
  So `%java-http-respond` / `-stream` / `-write` / `-finish` under a component QUEUE in the
  holder (the head a list, a chunk its octets, the end `t`) and send where the holder's
  task is the one running (`%java-host`) or once the head is out (a chunk and the end are
  the stream's, any task's: measured, `/held`'s last chunk is written under `/release`'s
  task); else the holder's bell rings and its driver sends. Every response is a head and
  chunks there, a whole one too: it is out when its exchange closes, however long its
  task stays in hand.
- **Measured** (2026-11-19, wasmtime 49.0.0, `examples`-like probes kept in
  `.todo/artefacts/k07-java-front-end-what-the-suspended-threads-slice-left/serve/`): a request to a handler sleeping 1.5 s and one 0.4 s
  later: the second answered at once by the SAME instance (`slept 2 now 3`); a task
  scheduled 700 ms past its handler's response ran (`later 4 ran`), and the instance was
  dropped a second after; a handler asking an origin that takes 1.5 s through
  `HttpClient.send`, and a request 0.4 s later: answered at once by the same instance.
  Which instance a request reaches is the host's: one whose requests all wait was handed
  the next request every time; of 4 requests at once to a warm instance 9 of 12 shared
  it and 3 started instances; after the idle second 4 at once started 4. The client
  (`wasmtime run`): the `waiting` program -- a thread asleep 20 ms that sends what ends
  the reply `main` waits for -- took 8.3 s and printed `never released` before (the
  origin's limit), 0.3 s and `released` now.
- **Sizes** (component bytes, before -> after): a server of one context 435,274 ->
  454,127 (+4.3%; `-Drontolisp.wasm.debug-func-sizes`: the holders, the pump and the
  timers 5 KB, three more resume bodies 4 KB, the delayed response's two steps 3 KB,
  `%java-http-send` 2 KB), its `--native` executable 8,080,040 -> 8,170,352;
  `examples/java/http/Server.java` 514,871 -> 535,488; `Httpbin.java` 684,081 -> 704,883;
  the client's `async` program 676,217 -> 689,395; a counter under four threads 115,429 ->
  118,196 (its loop a plain function; 114,995 before that: no loop over the time).
  **Trap, measured**: a `lambda` written in the value of a `(setf (aref ..) ..)` was
  compiled four times (four `_lambda_N` of 2,178 B each in every served component);
  `%http-delayed-begin` binds what it stores first.
- **Pinned by** `java-http-server-spec.yaml` `a-request-that-waits-is-ended-by-another`
  (`/held` writes a chunk and awaits a latch for an hour at most; the harness asks
  `/release` once it has read the chunk on the legs that send one as it is written, a JDK
  and the component, beside it elsewhere: `meanwhile:`; before k07 the component leg held
  the host for the 120 s of the test's client), `java-http-spec.yaml`'s `waiting` program
  (no `--native` leg: `.todo/k18`), `JavaLoweringTest` (the three variants, the loop, the
  exchange's two names, the responder handed over), the corpus's thread cases on the
  component leg.

## The lowering table

| Java | core forms |
|---|---|
| `a + b` (int) | `(%mask-signed-field 32 (+ a b))`, a call or a field operand as `(the (signed-byte 32) ..)`; `/` is `truncate` asserted `(integer -2147483648 2147483648)`, `%` `rem` |
| `int x = v;`, `int f(int p)` | `(let ((x v)) (declare (type (signed-byte 32) x)) ..)`; `(defun .. (p) (declare (type (signed-byte 32) p)) ..)` |
| `a << n`, `a >>> n` | `(ash a n)` wrapped; `(ash (logand a mask) (- n))` wrapped; a variable count `(logand n 31)` |
| `s + x` | `(%string-concat s' x')`, each operand by its type: `(or s "null")`, `(%decimal-piece i)`, `(%java-char-string c)`, `(if b "true" "false")`, `(%java-double-string d)`, `(%string o)` |
| `a && b`, `a & b` (boolean) | `(and a b)`; both operands bound first unless the second runs nothing |
| `x op= v`, `x++` | the place's operands bound once (`let*`) when more than a variable; the result cast to the place's type |
| `a * b` (long), `a >>> 3` | `(%mask-signed-field 64 (* a b))`; `(logand (ash a -3) 2305843009213693951)` |
| `f * g` (float), `x % y` (double) | `(%single-float (* f g))`; `(%java-double-rem x y)` |
| `(int) d`, `(byte) i` | `(%java-double-to-int d)`: toward zero, the type's ends outside it; `(%mask-signed-field 8 i)` |
| `new int[n]`, `{1, 2}`, `xs[i]` | `(make-array n :element-type '(unsigned-byte 32) :initial-element 0)` (each primitive its own, "Numbers"); `(vector ..)` / `:initial-contents`; `(%mask-signed-field 32 (aref xs i))` |
| `while`, `for`, `do` | `(while test body.. update..)`; `(block %break ..)` around it and `(block %continue ..)` around the body only when the body has that exit; `do` tests a flag set after the body |
| `for (T x : xs)` | an array: a counter, the length taken once; an `Iterable`: `(let* ((it (iterator xs))) (while (hasNext it) (let ((x (next it))) ..)))`, each call direct or dispatched as any call |
| `return v` | the value in tail position, else `(return-from %fn v)` and a `(block %fn ..)` |
| `switch (n) { case 1 -> a; default -> b; }` | `(cond ((eql n 1) a) (t b))`; a statement's `break` is `(return-from %break nil)` |
| `yield v` | the value in tail position of its arm, else `(return-from %yield v)` |
| `o instanceof Circle c` | `(and (Circle? o) (progn (setq c o) t))`, `c` a `let` of the statement; `if (o instanceof Circle c) a` is `(if (Circle? o) (progn (setq c o) a))` |
| `new Inner()`, a local class | `(Outer.Inner.new outer args.. captured..)` |
| `new int[n][m]` | `(let ((a n) (b m)) (let ((n' (%java-array-length a)) ..) (declare (type (integer 0 2147483647) n' ..)) rows))`, each level but the last a `while` filling a vector of `nil` |
| `f(1, 2)` to `f(int...)` | `(f (make-array 2 .. :initial-contents (list 1 2)))` |
| `x -> e` | `(lambda (x) e)`; `(%make-Iface%lambda (lambda (x) e))` where the program can tell the interface |
| `String::length`, `obj::m` | `(lambda (%t0) (length %t0))`; `(let ((%t1 obj)) (lambda (%t0) (m %t1 %t0)))` |
| `Long b = 5L`, `long n = b`, `int i = boxed` | `(cons 5 :long)`; `(car (or b (%java-null-unbox)))`; `(or boxed (%java-null-unbox))` |
| `above(T o)` with `T extends Comparable<T>` | `(defun Box.above(Comparable) (this o) ..)`; `held.compareTo(o)` is `(Comparable.compareTo(Object)%v held o)` |
| `xs.get(i)` on a `List` | `(java.util.ArrayList.get(int) xs i)` while `ArrayList` is the one list the program makes, else `(java.util.List.get(int)%v xs i)` |
| `a == b` (strings) | `(eq a b)`; every string constant of such a program is a global `%string<n>` set to `(%java-intern "text")` before `main` |
| `try { .. } catch (A \| B e) { .. }` | `(handler-case .. ((satisfies %catch0) (%t1) (let ((e (or (%java-thrown %t1) (%lisp-error %t1)))) ..)))`, `(defun %catch0 (%c) (let ((%thrown (or (%java-thrown %c) (%lisp-error %c)))) (or (A? %thrown) (B? %thrown))))` |
| `p.get()`, `xs.length`, `s.charAt(i)` | `(C.get() (or p (%java-npe)))`; `(length (or xs (%java-npe)))`; `(let* ((%t0 (%string-code s i))) (if (< %t0 0) (%java-string-index i s) %t0))` |
| `sb.append(x)` on a `StringBuffer` | `(let* ((%t0 sb) (%t1 x')) (let ((%t2 (%java-monitor-enter %t0))) (unwind-protect (progn (%java-sb-append %t0 %t1)) (%java-monitor-exit %t2))))`; a `StringBuilder`'s form in a program reaching no member of `Thread` |
| `synchronized (o) { .. }`, a `synchronized` method | `(let ((%t1 (%java-monitor-enter o))) (unwind-protect (progn ..) (%java-monitor-exit %t1)))`, the lock `this`, `'Class` for a static method and `Class.class`; `(progn (or o (%java-npe)) ..)` in a program reaching no member of `Thread` |
| a `volatile` field read, written | `(rontolisp:with-mutex ((%java-volatile)) (C.f o))`; `(let* ((%t0 v)) (rontolisp:with-mutex ((%java-volatile)) (setf (C.f o) %t0)))`; the plain access in a program reaching no member of `Thread` |
| `o.wait()`, `o.notifyAll()`, `t.start()` | `(Thread.await(Object,long) (or o (%java-npe)) 0)`; `(Thread.signal(Object,boolean) (or o (%java-npe)) t)`; `(Thread.start() t)`, whose body ends in `(%java-thread-start carrier daemon (lambda () ..))` |
| `System.out.println(x)`, `PrintStream p = System.out` | `(write-line x')`; `(java.io.StandardOutput.out())`, whose `p.println(3)` is `(java.io.PrintStream.println(int) p 3)` |
| `s.getBytes()`, `new String(bs)` | `(%java-utf8-bytes s)`; `(%java-utf8-string bs 0 nil)` |
| `Arrays.sort(ns)`, `Integer.parseInt(s)` | `(java.util.Arrays.sort(int[]) ns)` whose body is the native `(%java-sort-numbers a 0 (length a))`; `(rontolisp.internal.Support.parseInt(String,int) s 10)` |

## Measured (2026-11-17)

Linux x86-64 (64 threads), GraalVM JDK 25.0.5, wasmtime 49.0.0, best of three; javac's class /
the spike's lowering (JVM, precompiled wasm) / this front end's:

| program | javac | spike | front end | after j72 | `.class` / `.wasm` bytes (spike -> front end -> j72) |
|---|---|---|---|---|---|
| `Fib` | 0.14 | 0.21 / 0.18 | 0.19 / 0.18 | 0.20 / 0.17 | 25,978 / 5,194 -> 20,749 / 2,728 -> 20,746 / 2,728 |
| `WrapInt` | 0.18 | 0.23 / 0.11 | 0.21 / 0.11 | 0.20 / 0.11 | 28,119 / 5,017 -> 21,998 / 2,508 -> 21,995 / 2,508 |
| `Sieve` | 0.21 | 1.20 / 0.57 | 1.22 / 0.61 | 0.59 / 0.53 | 41,446 / 11,136 -> 35,936 / 10,380 -> 37,366 / 10,819 |
| `WrapLong` | 0.12 | 3.57 / 7.74 | 3.46 / 7.46 | 0.14 / 0.04 | 27,604 / 7,049 -> 21,519 / 4,544 -> 21,534 / 2,539 |

The programs are `.todo/artefacts/j68-java-front-end-spike/programs/`. A step at 300,000,000
steps (j72, the start subtracted): `WrapLong` 1.3 / 1.2 ns, `WrapInt` 2.8 / 2.4 ns. The corpus (47 cases,
5 standalone, 22 refused, 2026-11-17) and `examples/java/shop/Main.java` (six packages
of one class to a file, threads joined before it prints) print what the JDK
prints on four backends. After j71 the four programs are byte-identical but `Sieve`
(10,380 -> 10,497: `Math.sqrt` is `%strict-math`).

### j77 (2026-11-17)

Same box, best of three (the box's load moved absolute times by up to 15% between runs, so
each pair was run interleaved); each program's IR (`--dump-ir`) edited by hand for the variant.
JVM / precompiled wasm / precompiled component, seconds:

| question | as lowered | variant | verdict |
|---|---|---|---|
| a pattern's binding, 100,000,000 tests `if (s instanceof Circle c) .. else if (s instanceof Square q) ..` | 1.08 / 3.57 / 3.62 | `let` in the taken branch 1.09 / 3.25 / 3.37; the variable kept, its stores at the head of the branch 1.05 / 3.24 / 3.38 | the cost is the `and`'s truth value, not the variable: `branch` |
| a dense `int` switch, 100,000,000 `step(i % 12, acc)` over 12 cases | 2.30 / 1.02 / 1.02 | `case`: the same IR, it expands to the same `eql` chain | nothing to choose |
| a lazy class's test, 300,000,000 static calls of `x * 31 + base` | 0.96 / 5.31 / 5.31 | the class eager 0.99 / 4.93 / 4.93 | 1.3 ns a call on wasm, none on the JVM |

- The switch: a comparison costs 0.56 ns on wasm (the case taken always the first 0.75 s,
  always the last 1.37 s). javac's class runs it in 0.28-0.36 s, its if-chain and its
  `tableswitch` alike; the JVM's 2.30 s is not the chain's: 0.36 s when only 8 of the cases are
  taken (`i & 7`), 1.95 s with 16 (`i & 15`) -- the method once every arm of it runs
  ([hot-path-method-size.md](hot-path-method-size.md)).
- Lazy in a real program: `examples/java/shop` leaves one class lazy, `Discounts`, whose
  initializer makes a method reference: the lowering records a lambda body's effects into the
  function making it, so what `Line.listPrice()` reaches is counted as run by the initializer.
  Not changed: leaving them out needs every call through a functional interface -- an
  intrinsic's `funcall` of a lambda argument included -- to count the bodies of its lambdas, to
  save the 1.3 ns above on wasm only.
- An anonymous class inside a lambda: a JDK 25 numbers it in the order written, with the call
  around it resolved among overloads, a generic method's call and nested lambdas included, and so
  does the front end (pinned: `a-local-and-an-anonymous-class-keep-the-locals-they-read`).

### j78 (2026-11-17)

Same box and method (`--dump-ir` edited for the variant; JVM / precompiled wasm / precompiled
component seconds, best of three; the interpreter on the same IR at a fiftieth to a tenth of
the rounds):

| question | as lowered | variant | verdict |
|---|---|---|---|
| the cast a compiler puts where a value is read through a type argument: 5*10^7 rounds of `xs.get(i).length()` and `ns.get(i)` (`List<String>`, `List<Integer>`) | 1.20 / 2.69 / 2.76; interpreter 4.15 | `(or (null v) (simple-string-p v))` / `integerp` before each use: 1.07 / 3.79 / 3.66; 5.08 | trusted: the JVM pays nothing, wasm 6 ns an `integerp` and 18 ns a `simple-string-p` (+40% on this loop) for a heap only a raw type or an unchecked cast pollutes |
| `(or box (%java-null-unbox))` where the box was just made: 3*10^8 rounds of `Integer b = i & 1023; Long l = ..; total += b + l` | 0.39 / 1.93 / 1.90; 8.65 | the test dropped: 0.35 / 1.82 / 1.83; 7.15 | kept: 0.17 ns a test on wasm, none on the JVM; only the interpreter would gain, for a flow analysis of where a box was made |
| the `Long` cons now that `long` arithmetic is one machine operation: a 10^6-element `List<Long>` summed 20 times plus 3*10^7 boxings and unboxings | 1.56-1.69 / 1.78-1.82 / 1.83; 11.33 | a `Long` the bare integer (no `cons`, no `car`): 1.44-1.46 / 1.46-1.61 / 1.49-1.51; 10.73 | kept: 10-20% of a loop that does nothing but box, and the cons is what tells a `Long` from an `Integer` (`equals`, `getClass`, a pattern, a `HashMap` key) |

## What a compiler reports and this does not, stated in `doc/*/java/deviations.md`

Checked: `private` (the unit is the top-level class), the access of a class or member from
another package ("A program of several files"), assignment to a `final` field or local, and
what the flow decides ("The flow"): unreachable statements, a missing `return`, definite
assignment and unassignment of locals and blank final fields, effectively final captures; a
written type argument outside its parameter's bound and a field written through `? extends`
(j78); checked exceptions (j99: "Exceptions"). Warned: the unchecked warnings ("Warnings")
and a catch clause nothing reaches. A value read
through a type argument is trusted (no cast where it is used: measured below, "j78"), and a
store into an array of references is not checked (measured: "The class of an array"). The run time's exceptions are the
language's, but a `NullPointerException` has no message, a `ClassCastException` says nothing
of modules and loaders, and one out of a static initialization is not wrapped ("Exceptions").

## Tests

`JavaSpecE2eTest` over `java-spec.yaml`: the cases concatenated into one file behind a class
calling each case's `main`, run under the JDK running the test (the oracle leg: an expectation
that is not the JDK's fails there), the interpreter, the JVM, wasm and the component; a
`standalone:` case is a file of its own (the launch rules, an uncaught exception: the JDK's
first line and the status 1 on every leg, `at:` the end of the location line under it on the
interpreter and the JVM; `stderr:` the whole standard error but a JDK's frame
lines); a `refused:` case is the scaffold with the case's statements, which the JDK has to refuse with
`error` at that line and the front end at the same line with a message starting the same. In
process, part of `./mvnw test`. The interpreter's `standalone:` leg has the 300 s a process of the
other legs has (`CliStack.callWithin`): a program whose threads wait for ever fails its leg
by name and the run goes on (until k14 it stopped the run: full run 19, 50 minutes). The leg
ends the threads the program left, daemons, as the other legs' process end does
([threads.md](threads.md), "A program's end ends its threads").
**No thread case orders two threads by a sleep or a delay** (k14, 2026-11-19): what one
thread prints before another does is held by a latch the other waits for, or by a wait for
the state the line prints (`while (..) Thread.sleep(1)`); a sleep stays where it IS the
subject (a monitor held across one, a wait's limit, a task's time). Seen under load before:
`Turns`' spinning loops stopped after `Thread.sleep(5)` with no turn taken (now each says
its first turn through a `Runnable` -- an interface, so that the loop's turn on wasm is
still its test's alone: the same loop over a plain field keeps its turn there, measured);
`Timed`'s lines around a 20 ms timer task and a `purge()` after `Thread.sleep(60)`, 6 of
32 copies on the interpreter, 96 programs at once, load average 11 rising to 63 (now 0 of
408 copies of the thread cases, 14 rising to 152; `check.sh` beside `run.sh` in the
artefacts named under "java.util.concurrent");
and under a JDK a `ThreadPoolExecutor`'s text saying `pool size = 1` after
`awaitTermination` answered true (the case waits for the text). A limit whose expiry would
change a line is 60 s, never 1 (k08 found one left, `Waits`' `finished.join(1000)`). The
one limit that HAS to pass with tasks beside it, `invokeAll`'s in `Handing`, is asked of
a pool of one thread started beforehand: the tasks behind the one held never run,
whatever the load.
`JavaLoweringTest` (the table as emitted forms, names and constants, the
refusals and the compile errors with their positions;
`theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`; `aProgramOfSeveralFilesIsReadFromItsSourceTree` over
an in-memory `JavaFiles`: the refusals of the source tree, the imports and the access;
`aLibraryOfTheSourcePathIsLoweredAsFarAsTheProgramReaches` over one with a `library`: what
is lowered and what is not, the refusal of a reached body with its `reached through`
lines, of a class nobody has where the program asks what it is, of a class under one, the
access, a program's annotation of no type and of a class). A library on the source path
on four backends and under a JDK is the `standalone:` case
`a-program-uses-a-library-read-as-source-from-its-source-path` (`library:` / `provided:`),
the command line's two options and the coordinates
`JavaClassPathCliTest#aJavaProgramCompilesItsLibrariesFromTheSourcePath`. A
`standalone:` case may carry `files:` (path to text) written beside it, its `file:` a path
below its package's directories (`a-program-of-several-files-finds-its-classes-by-package`).
java-spec `refused:` cases of the inner class types and the bounds
(`a-member-of-an-inner-class-typed-through-the-arguments-around-it`,
`a-type-argument-outside-its-parameter-s-bound`, `a-field-written-through-an-extends-wildcard`),
`SourceLanguageTest#javaClaimsItsExtension` (`isComplete`),
`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (a session on a pipe:
the late binding, a subclass added, static state, the echoes, a step's exception; `test` and
`format` refusing), `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram`,
java-spec `refused:` cases of the annotations and of the flow (each kind of branch an
assignment may be skipped on, a blank final assigned twice and in a loop, a blank local
captured, a missing `return` past a `do`, a labeled block and a switch with no default, a blank
field no constructor assigns, a switch rule with no value, an inner superclass with no instance
around it), the corpus cases `a-loop-is-a-while-and-its-exits-are-blocks` (what the flow
accepts), `a-nested-class-is-static-or-holds-the-instance-around-it` (`o.super(..)`, `o.new I()
{ }`, `Outer.super.m()`, `Outer.this` ahead of `super(..)`) and
`an-interface-has-defaults-static-and-private-methods-and-constants` (`I.super.m()`),
java-spec `a-byte-stream-reads-and-writes-bytes-and-a-string-is-its-bytes`,
`a-print-stream-a-reader-and-a-writer-over-streams-and-system-out-as-a-value` and the
`standalone:` case `the-standard-input-is-read-as-bytes-and-through-a-reader` (a case's
`stdin:` is what every leg reads), `ListIterator`, `Collections.binarySearch` and
`parseUnsignedInt` in the cases of the utilities and of the boxes,
`JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles`, java-spec
`the-streams-that-hold-nothing-take-back-follow-one-another-and-count-lines` and
`a-matcher-keeps-a-region-tells-what-it-read-and-appends-its-replacements` (j87; the
`Locale` forms are lines of `format-renders-the-conversions-programs-write`, the host's
line with `\r\n` and a lone `\r` of `the-standard-input-is-read-as-bytes-and-through-a-reader`),
`SchemeLibraryTest#aCommonLispProgramReadsNoFrontEndsLibrary` (the gate), java-spec
`an-array-is-a-vector-with-the-default-of-its-element-type` (each array's class through
`Object`, a generic method's `instanceof`, a row of a widened grid, `copyOf` and `clone`, the
cast inferring alone, `deepToString`) and `the-boxes-parse-print-and-compare-and-math-is-strict`
(`ulp` .. `scalb`), `JavaLoweringTest#anErrorTheLoweringDependsOnIsReportedAtItsPosition` (an
array cast no value passes; k06: which flow error comes first, each kind's order), `DocExamplesTest` (a
` ```java ` fence is a whole program, its output the plain block after it; `deviations.md`'s
pins the class of an array no record names), `ExamplesE2eTest`
(`examples/java/shop/Main.java`, read with the files of its source tree), the `standalone:`
cases `threads-are-started-joined-and-hold-monitors` (the classes a JDK synchronizes, a class
initialized while another thread initializes another, `getState`, `InheritableThreadLocal`),
`an-uncaught-exception-ends-its-thread-alone` (the handlers),
`locks-latches-queues-and-executors-hand-work-between-threads` and
`threads-share-concurrent-collections-and-completable-futures` (j83). The `standalone:` case
`the-unchecked-warnings-are-printed-before-the-program-runs` (j86): its `warnings:` is the
start of the standard error under the JDK and the interpreter, and what the compile prints
for the JVM and both wasm legs. j88: java-spec
`a-static-initialization-that-throws-leaves-its-class-unusable`, the `charAt` lines of
`the-run-time-raises-the-language-s-exceptions` (each arm of `%string-code`), the
`standalone:` cases `an-uncaught-exception-of-the-program-s-own-class` (the causes, then
the location line: `at:` is the first line starting `  at `),
`the-class-the-program-starts-at-fails-its-initialization` and
`an-uncaught-exception-ends-its-thread-alone` (a thread's causes, a class failing in a
thread); ci-spec `string-code-reads-a-character-s-code-or-minus-one`;
`JavaLoweringTest#aMonitorIsEnteredAndLeftAndAThreadIsTheRunTimes` (both forms of a
failing initialization, `%uncaught`, the name function that ends in no run-time answer).
j89: the `standalone:` case `a-scheduled-pool-and-a-timer-run-tasks-at-their-times` (a
`TimerTask`'s `state` and `period` hiding no local
of the program; where an order by time is asked the delays are wide on purpose, 600
against 20 ms, since the legs run beside each other, and what two threads ran is sorted
before it is printed), the second halves of
`locks-latches-queues-and-executors-hand-work-between-threads` and
`threads-share-concurrent-collections-and-completable-futures` (static methods called
from the end of `main`: each new class, the dependents' order, the refusals' messages, the
text forms through `shown`, which drops the object's number), the `LocalBase` lines of
`a-local-and-an-anonymous-class-keep-the-locals-they-read` (a private field and method
above an anonymous class) and the `refused:` cases
`a-private-field-of-a-superclass-is-not-read-by-its-simple-name` /
`a-private-method-of-a-superclass-is-not-called-by-its-simple-name`. k00: the
`standalone:` case `threads-wait-for-one-another-again-and-again` (what one stack refused
or never ended: a buffer of two, turns, loops another thread ends, a held monitor's entry,
cleanups and catches that wait, a pool's idle thread, `invokeAny`, a barrier's second
round), `JavaSpecE2eTest#aProgramWhoseThreadsAllWaitIsRefusedWhereThreadsTakeTurns`,
`JavaLoweringTest#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`. k01: the
third parts of `locks-latches-queues-and-executors-hand-work-between-threads` (`stamps`,
`phases`, `transfers`, `hooksAndSizes`: the stamps' numbers, a phaser's tiers and its last
arrival's answer, a pool asked around its tasks, a pool with no core thread, a task that
threw counted, the wrappers) and of
`threads-share-concurrent-collections-and-completable-futures` (`accumulated`,
`stagesLeft`, `completers`), `asked` in
`a-scheduled-pool-and-a-timer-run-tasks-at-their-times`, the `ClassesHiding` lines of
`a-class-is-a-structure-and-a-constructor-a-function` (a hidden field, `super.x`, a
private field above), the last line of
`a-nested-class-is-static-or-holds-the-instance-around-it` (the identity text), and in
`JavaLoweringTest` the structures' names, the refusal of a field through a qualified
`super` and `Hooked` made only where a hook is declared
(`theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`); the `refused:` case
`a-field-through-super-in-a-static-method`. j98: the methods added at the end of
`a-string-has-its-everyday-surface-and-two-constants-of-a-text-are-one` (`rest`),
`the-boxes-parse-print-and-compare-and-math-is-strict` (`bits`: the bits, `decode`, the
unsigned parses, `Math`, `Character` over 19 code points),
`a-deque-an-optional-the-utilities-and-the-older-classes` (`later`),
`the-functional-interfaces-of-the-library-are-source-in-the-subset` (`conversions`),
`format-renders-the-conversions-programs-write` (`hashes`: `%h` of a class of the
program), `the-methods-of-object-on-a-class-that-declares-them-and-on-one-that-does-not`
(`classes`), `try-catch-finally-over-one-condition-holding-the-thrown-object` (`named`)
and `a-byte-stream-reads-and-writes-bytes-and-a-string-is-its-bytes` (`wide`: the three
UTF-16 charsets through a string, a reader given 1 to 3 bytes at a time, a writer and a
`PrintStream`; the names); the `refused:` cases `two-class-literals-of-two-classes` and
`a-class-literal-of-no-class-under-the-object-s-type`; the `at the end: null` line of
the standard input case; `JavaLoweringTest`'s refusals and, in
`theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`, a charset named by a constant
reaching no `Utf16` where a computed name does. A corpus program holding a surrogate
with no partner makes it with `new String(char[])`, never as a constant (wasm reads a
constant's as `?`). j99: the 13 `refused:` cases from
`a-checked-exception-thrown-and-neither-caught-nor-declared` to
`a-throws-clause-of-no-throwable`, `checked` in
`try-catch-finally-over-one-condition-holding-the-thrown-object` (what must be ACCEPTED:
the rethrow, the inferred clause, `close()`, a `finally` that drops, the charset name),
the last warning of `the-unchecked-warnings-are-printed-before-the-program-runs`,
`JavaLoweringTest#anErrorTheLoweringDependsOnIsReportedAtItsPosition` (the columns) and
`#theLibrarysSourceIsJavaAJdkCompiles` (the clauses against the JDK's),
`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (a session's
step and a session's method), ci-spec
`a-cleanup-a-return-from-runs-is-outside-every-handler-it-left` (a cleanup's own exit). k05:
java-spec `a-duration-counts-seconds-and-nanoseconds`, the lines at the end of the string,
the boxes and the utilities cases (the exact quotients, the ranges of a string, `toChars`
into an array, `OptionalLong`, `getenv` of no variable), `Thread.sleep` of a `Duration` in
`threads-wait-are-notified-and-interrupted`; java-http-spec
`a-request-and-a-connection-have-time-limits` (the one program naming a time limit),
`firstValueAsLong` in `fields-both-ways` and the timeout of a program's own request class
in `subset`; `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`
(the fetch form with no timeout keyword, and with both once a builder is given a time
limit). k12: java-spec `a-stream-hands-its-elements-on-one-at-a-time` (the laziness, the
count grid, the close handlers, what a stream refuses),
`the-streams-a-program-writes-over-collections-arrays-texts-and-numbers` (the chains of
everyday code; the number streams, their optionals, statistics and iterators; the
compensated sum over 200 seeded doubles; `Name.class::isInstance`) and
`a-collector-gathers-a-stream-and-the-type-arguments-are-found` (every collector, the
bucket order, the marks, a collector's parts combined by hand, and `Gathering`: the
collector shapes over the program's own classes, which hold the inference without the
library), `compared` in `the-run-time-raises-the-language-s-exceptions`;
`JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (a program
calling no stream holds no class of them; a stage is a `funcall`, a member's call direct).
The three cases print 589 lines; the corpus is 61 cases. k30: the `standalone:` case
`a-class-is-a-value` (a file of its own, so that the corpus program holds no class
object: each kind of type, the object of what a value is through `Object`, an
interface and a class, a map keyed by classes, the constants and the initialization of
an enum, the casts' messages), the `refused:` cases
`a-class-held-compared-with-a-literal-of-another` and
`a-class-literal-assigned-to-the-class-of-another`,
`JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (an object
made with what the program reaches and nothing else; a program comparing classes makes
none) and `#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition`
(`Class.getInterfaces()`); the last line of
`a-type-argument-is-found-from-the-arguments-the-target-and-the-lambdas` (a generic
method calling itself, a creation with `<>` of the class it stands in, `Name.this` in
an anonymous class of `Name`, two captures given for one type parameter). k35: java-spec
`dates-times-and-instants-are-read-from-their-texts-and-computed-with` (each class's
texts with the three kinds of refusal, the members, every field and unit, the zone ids,
a fixed clock, the amounts; where a JDK lists fields in an order of its run, the words
before them); the class nobody has of `aLibraryOfTheSourcePathIsLoweredAsFarAsTheProgramReaches`
and of `a-program-uses-a-library-read-as-source-from-its-source-path` is
`java.util.Currency`. k26: java-spec
`a-gatherer-is-a-stage-holding-what-it-pushes-until-it-is-asked` (93 lines: `Gatherers`,
gatherers written by hand and joined, the defaults, a gatherer of the program's own
class); `closing` and `several` in `a-stream-hands-its-elements-on-one-at-a-time` (when an
inner stream is closed, `mapMulti`), `numbers` in the spliterators' case (the
spliterators of numbers, a stream's own where it reports what a JDK does), `drawn` in the
streams' case (a `Random`'s streams, a thousand seeded ranges as one number), `concurrent`
in the collectors' case; `mapped` in the `standalone:` case
`threads-share-concurrent-collections-and-completable-futures` (`mapConcurrent`: threads,
so no line of the corpus program). A line where this differs from a JDK by decision (a
`push` in the middle of an element, a `peek` after `mapMulti` under a `limit`, the marks
of a stream's spliterator) is in no case: the JDK leg would refuse it; the probes of the
k26 artefacts hold them. The corpus program is past one class's constant pool on the JVM
since these lines, so its leg loads the `$PartN` classes beside the class
(`JavaSpecE2eTest.runOnJvm`).
