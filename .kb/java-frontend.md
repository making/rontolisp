# The EXPERIMENTAL front end for a subset of the Java language (`am.ik.rontolisp.java`)

**Invariant: a Java file is read, typed and LOWERED to the Common Lisp core forms the pipeline
already consumes; no backend learns a Java name; a construct the subset does not have is
refused BY NAME at its position, never lowered as something else.** Status is experimental -- "an
experimental subset of the Java language", no compatibility claim -- and every user surface says
so (`--source-language` help, `doc/*/java/`, `examples/java/*/README.md`). Provenance and naming
rules: [java-frontend-provenance.md](java-frontend-provenance.md). `--no-gc` compiles a reactor of
scalar values ("A reactor on `--no-gc`" below). A section's heading names the item that made it.

## Where it sits

- `java` depends on the AST types and `reader` (`LispReadException`) only (`PackageCycleTest`),
  its lexer and parser its own, and is reached ONLY through the seam `eval/SourceLanguage.JAVA`
  (`.java`, `--source-language java`; [source-language.md](source-language.md); "A program of
  several files", "A session" below).
- The run-time half is `eval/java.lisp`, spliced by `eval/JavaLibrary` the `StarlarkLibrary` way
  (the interpreter loads it at the first `rontolisp::%java-` FUNCTION it resolves; compiled,
  `LibraryDefunPruner` drops the unreachable). Name gate: a member starting `%JAVA-`.
- **Library classes are of two kinds** ("The library" below): INTRINSIC (`JavaIntrinsics`: a Lisp
  value or no value, its members emitters of forms under the program's overload resolution) or
  JAVA SOURCE in the jar (`src/main/resources/am/ik/rontolisp/java/library/`), lowered as far as
  the program reaches it; `resource-config.json` carries both for the native image.

## The decisions (2026-11-17)

- **Static types pick the form.** Every expression lowers to `(form, type, constant)` (`Typed`); a
  conversion (widening, boxing, a number's text) is made where the types say, never tested at run
  time.
- **`null` is `NIL`; `boolean` is `T`/`NIL`; no box is `null`.** A boxed number is the number, a
  boxed `char` a CHARACTER (`Object o = 'c'` prints `c`), a boxed `true` `T`, a boxed `false`
  `:false`, so `Map<String, Boolean>.get` tells a stored false from a missing key ("Boxes" below).
  Rejected: `null` as `:null` (every reference test and slot default would pay, not only a
  `Boolean` box).
- **`int` is an integer kept in range** by `(%mask-signed-field 32 x)` (`short`, `byte`, `long` at
  16, 8, 64 bits), `char` an integer its static type prints, `float` the double of its value
  ("Numbers" below).
- **A class is a `defstruct` named by its BINARY name** (`Outer$Inner`; `JavaNames.structure`),
  the one class name the run time reads (`Object.toString()`); cost: the made `java.lang` classes'
  longer names (109,069 -> 109,253 B of wasm for a counter under four threads), nothing where none
  is made. `extends` is `:include`; a field named as one above it is the slot `name%n`
  (`JavaClass.Field.slot`), the static type it is read through picking the accessor (`super.x` is
  `this` typed as the class above). A constructor doing nothing is never called.
- **Names** (`JavaNames`): verbatim when holding an ASCII lowercase letter, else `j%` + the name,
  so `T`, `NIL`, `LIST`, a class `A` never meet the pipeline's. A class's Lisp name is its
  qualified name, a `java.lang` class's its simple one; a program's class named as a `java.lang`
  class it uses is refused.
- **The program is closed, so a call is direct when it can be**: one body under the receiver's
  static type is a plain call, several a `cond` over the structure predicates, deepest owner
  first, ending in `%java-no-receiver`; a receiver that can be a lambda is tested by `functionp`
  first ("Lambdas" below). `super.m()` and a `private` method are direct.
- **Overloads** go by the arguments fitting with no boxing, else with it, else as variable arity
  elements, then the one whose parameters convert to every other's, else
  `reference to .. is ambiguous`; a lambda and a method reference wait for the chosen parameter's
  type ("Inference" below).
- **Constants** are folded as the language computes them: a constant variable is its value at
  every read, whatever the order of the classes; `char c = 'a' + 1` is a `char`.
- **Static initialization: at first use where it shows, before `main` where it cannot**
  ("Static initialization" below).
- **An exception is one condition type holding the object** (`%java-throwable`), its `:report` the
  program's `Throwable.toString()`, `finally` `unwind-protect`; what the run time raises is
  converted AT THE CATCH ("Exceptions" below).
- **The program runs inside
  `(%reporting-uncaught #'%java-uncaught-line (%host-exit 1) (%initialization) (main))`**: what
  nothing caught is a JDK's line, the pipeline's location lines under it and status 1 on four
  backends, no handler holding the condition; `%initialization` only where a class is initialized
  before `main`.
- **`instanceof` / a cast**: a class is its predicate, an interface the generated `Iface%is` (its
  topmost classes', library values' and lambdas' predicates). A cast to a type variable tests its
  bound, to a parameterized type its class, none where only type arguments tell it from no cast;
  `instanceof` a type the erased program cannot answer is javac's `X cannot be safely cast to Y`;
  a failing cast throws `ClassCastException()`. To `int[]`, `byte[]`, `float[]`, `double[]`,
  `boolean[]` from a non-array type it is `typep` of the element type, to another array type or
  from one `%java-array-is` over the class a widening recorded, with no store check ("The class of
  an array" below, measured there).
- **`Object`'s methods** through a type none of whose classes declares one are the program-wide
  `%string` / `%equals` / `%hash` (the declaring classes deepest first, then `%java-to-string` /
  `-equals` / `-hash-code`; a structure's identity is a number an `eq` table keeps from the first
  ask). `super.toString()` / `.equals(o)` / `.hashCode()` with no class above declaring it is the
  run-time function on `this`; any other `Object` member through `super` is refused by name. **A
  class compared with a class, or asked its name, is its name**: compared (`==`, `!=`, `.equals`:
  `equal` binary names, `JavaLowering.sameClass`) or before `.getName()` / `.getSimpleName()`,
  `getClass()` and a class literal make no object, elsewhere the class object ("A class as a value"
  below) -- the `equals` an IDE writes (`getClass() != o.getClass()`), the one use a probe of
  everyday code met. `==`'s `incomparable types: Class<..> and Class<..>` is javac's over the 16
  shapes asked but one: an interface beside a `final` class not implementing it is compared.
  `Name.class::isInstance` / `::cast` test / cast their argument, the literal never lowered.
- **A class of Java source stands under `Number`, which stays an intrinsic**: a class extending it
  keeps `superclass == null` and names it in `JavaClass.intrinsicSuperclass`, which only the TYPE
  relations read: no structure included, no constructor called, no body inherited. Rejected:
  `superclass` set to the intrinsic (the 60 other walks of `superclass` would each learn that a
  class above can have no structure), and `Number` as Java source with the boxes under it (a box
  is a Lisp value: every `instanceof` and member through `Number` would pay where no such class
  is). Only where the program can hold such an instance (`isExtended`) does a call of one of the
  four abstract members through `Number` dispatch over the made classes, deepest first, then the
  boxes' form (`|Number.intValue()%v|`); `instanceof`, casts and patterns ask `|Number%is|`,
  `Object`'s three go to `%string` / `%equals` / `%hash`. A program with none is byte-identical
  (`Plain`, `Count`, `examples/java/shop`; `sizes/` of the k08 artefacts); a class of its own took
  `Plain` from 126,407 to 132,331 B of wasm. One of the four left out is javac's
  `.. does not override abstract method doubleValue() in Number`. `AtomicInteger`, `AtomicLong`,
  the adders and accumulators extend it in the library's source. Pins: `standalone:` case
  `a-class-of-java-source-stands-under-number` (a file of its own, keeping the corpus program's
  plain lowering), `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (both
  lowerings of one source), `refused:` case
  `a-class-under-number-declares-the-members-it-leaves-abstract`.
- **The abstract method a class leaves out is named as javac names it** (seven shapes asked): the
  one declared LAST in the first type having one, superclass chain before interfaces; `super.m()`
  of an abstract method says `in <class>`.
- **An intrinsic class counts in a dispatcher and an interface test only where the program can
  hold its values** (`JavaClass.held`: named by a program's declaration, a lowered body or a
  boxing), not by being in the table, which library member types fill (`Collection.stream()`
  reaches `Double` through `Stream`, `DoubleStream`): every program with a collection would carry
  a `FLOATP` arm and `%java-double-compare` in `Comparable%is` and `Comparable.compareTo(Object)%v`
  (`examples/java/shop` 328,194 -> 328,869 B of wasm without the flag, 328,252 with it, the rest
  being `compareTo`'s null test).
- **A simple name finds what a class HAS, not what a class above it declares** (JLS 8.2,
  `JavaLowering.inherits`): a `private` member, and from another package one neither `public` nor
  `protected`, is no member of the classes under it, so the name goes on outward (`use(name)` in a
  `new Thread() { .. }` reads the local, not `Thread`'s private `name`). With nothing else of the
  name, a field is javac's `x has private access in A` /
  `is not public in A; cannot be accessed from outside package`, a method `cannot find symbol`
  (asked of javac: the corpus's two `refused:` cases).
- **The string model: a string's elements are code points** (the host CHARACTER); `hashCode` is
  the specified number over the UTF-16 form. The host's `princ` of a double IS the language's text
  but for the exponent letter (measured 2026-11-17: 628 doubles, four backends).
- **Where a program starts** (measured against a JDK 25 source launcher): the file's first type
  when it has a `main`, else the class named as the file and no other; `main(String[])` before
  `main()`, declared or inherited, not private; an instance `main` runs on an instance of the
  class DECLARING it. The array is the program's command line past its name,
  `(coerce (cdr (%host-argv)) 'simple-vector)`: `--`'s arguments on the interpreter, the host's on
  the JVM, wasm, the component and `--native`, nothing on a `--no-wasi` module -- read only where
  `main`'s body names its parameter (`readsItsArguments`), else `(vector)`, the module
  byte-identical with no argv import (measured 2026-11-24: reading them costs a program printing
  its arguments 19,893 -> 23,519 B of wasm). With no `main`, the `@WitExport` classes of its
  source tree are a component's entry points (l85, the user's decision of 2026-11-24 that the
  premise of a `main` goes: "WIT exports" below); with none of those, a `--no-wasi` or `--no-gc` module
  exports the public static methods of the class it starts at ("A reactor of public static
  methods" below); every other target refuses it.
- **Lowered until the class table is still**: a library class is read where a body first names
  it, possibly after a call whose dispatch it changes, so `JavaLowering.lower` starts again
  whenever a pass moved the table's version.

## A program of several files (2026-11-17, j74)

- **Found where first named, as a source launcher finds them** (measured against JDK 25's
  `java app/Main.java`): a name the table and the library miss reads
  `<root>/<package>/<Name>.java` (`JavaFiles`), the root a `../` per name of the starting file's
  package; a file read adds all its classes. A file not declaring the class in the package its
  path spells is `bad source file: .. file does not contain class p.Q`, a starting file whose
  directories do not spell its package
  `end of path to source file does not match its package name p` (the launcher refuses a single
  file so too). `-e` reads no other file.
- **The unit of lowering is the program**: a class found moves the table, so the pass is lowered
  again and calls and dispatch sets see every class of every file, each lowered whole and located.
  `(load "app/Main.java")` runs the program as `rontolisp app/Main.java` does, on both paths; a
  file with no `main` is refused as an entry. Two loads sharing a class are two closed programs,
  the second defining the shared names and dispatch sets again.
- **Imports**: a single-type import of the program's class is resolved where written (javac's
  `cannot find symbol` there), one of the library where used, so a program holds only the library
  classes it uses; a static import is checked where written (`cannot find symbol: static m`,
  `cannot find symbol: class C`), and an unqualified call no class around has a method of takes
  every single-static import of the name together. `import p.Outer.Inner` is the member type under
  the longest prefix that is a class (`JavaClasses.canonical`). Trap: a file's member classes are
  declared before its imports are looked for, else a record component of the imported type is
  `cannot find symbol: class Inner` where a body, lowered later, finds it. Pin:
  `JavaLoweringTest#aProgramOfSeveralFilesIsReadFromItsSourceTree`.
- **Access across packages** (`JavaLowering.checkAccess`), on the program's classes only: a
  non-`public` class from another package, a member with no modifier and a `protected` one from
  outside its package and subclasses are javac's errors
  (`X is not public in p; cannot be accessed from outside package`,
  `m() has protected access in C`). Not checked: the receiver rule of a `protected` instance
  member, a `protected` constructor reached by `new` from a subclass (`deviations.md`). javac
  drops an inaccessible candidate from the overloads
  (`no suitable constructor found for A(no arguments)`); ours is chosen, then refused by its
  access.

## A library from the source path (2026-11-19, k24)

**Invariant: a library a Java program uses is its SOURCE, lowered with the program on every
backend as far as the program reaches it; what the program reaches and the subset lacks
is no error until it runs: the statement naming it warns where the program is compiled and
throws a JVM's link error where it runs (l86, below). Never refused where a class is read; in
the program's own files refused where written.** Asked for YAVI
("YAVI compiled to wasm" below).

- **Source, not bytecode**: a dependency's `-sources.jar` (or a directory) is read through the
  program's `JavaFiles`, one lowering for the four backends. Rejected: a `.class` front end -- a
  second front end with its own semantics (erased generics, no constant folding as the language
  computes it, `invokedynamic` lambdas and concatenation, synthetic bridges), a second leg for
  every corpus case, and a jar calls the JDK by descriptor where the library here is found by
  source name and static type; it would add only jars shipping no sources (YAVI and jspecify both
  publish one).
- **Surface**: `--java-source-path DIRS-AND-JARS`, `--java-source-dep G:A:V` (`cli/JavaSourcePath`).
  **Not `--java-dep`**, class files for the host's class loader, refused with a `.wasm` output and
  copied beside a compiled jar: a source-path library is compiled INTO every output, and one flag
  for both would have a jar carry classes its program never calls
  (`JavaClassPathCliTest#aJavaProgramCompilesItsLibrariesFromTheSourcePath`). Coordinates resolve
  through `am.ik.maven` to the runtime class path's `sources` jars; a NAMED coordinate without one
  is refused, a transitive one warned (javac needs only its class file; the program may never
  reach it). The Maven plugin's `wasm` goal passes a project's dependencies (`jvm-export.md`, "The
  plugin's `wasm` goal"); `-e` and a session read the roots. **The playground has none**: its files
  are text (`rontoPutFile(name, content)`), it has no resolver; its uploads are the program's
  source TREE ("The playground's uploads" below).
- **A program's file wins**: `JavaClasses.programClass` asks the source tree, then the roots in
  order (javac's source-path order); a class of a JDK's packages is looked for on neither.
- **Three origins** (`JavaClass.Origin`): `PROGRAM` lowered whole, located, checked; `LIBRARY` (the
  jar's own) lowered as reached, trusted, not located; `DEPENDENCY` lowered as reached, LOCATED
  (an uncaught exception's `at`: `geo-sources.jar!/geo/Table.java:52 in geo.Table.row(Object)`),
  access-checked, but not for what a compiler checked (`@Override`, abstract methods left out, the
  flow, `throws`, type-argument bounds, warnings).
- **A class nobody has is a name until reached** (`JavaClass.absent`): a class a dependency's
  DECLARATION names that is missing, lacking from the subset or refused in its own file is a
  `JavaClass` holding the refusal, outside the table, standing in member types where identity is
  enough (a method taking a `java.util.Currency` stays an overload); asking what it is made of
  throws `JavaClass.Absent`, the refusal at the asking expression, and a class whose supertype is
  absent is withdrawn (javac's `class file for X not found`), and so is a class read under
  it meanwhile (its file read while the withdrawn one's members were: a JVM loads no class
  whose supertype it cannot load; `JavaClasses.withdraw`, which also takes it out of
  `lambdaTargets`, `made`, `used` -- left there, a whole-program walk asked it
  `isSubclassOf` and the `Absent` escaped as `library/java/util/HashMap.java:1:1: ..`). A
  reached BODY names one as a name too ("What a library's body names that the subset
  lacks" below). Rejected: dropping a member whose
  signature does not resolve (a lost overload can change a call's pick: `f(null)` beside
  `f(Object)`), and refusing the class (YAVI's `ValidatorBuilder` named `java.time` in its
  `constraint` overloads while the library lacked it: no program would have got past it).
- **Declared whole, lowered as reached**: a class named is parsed and its members' types resolved,
  reading the files they name: the classic YAVI program declares 171 of 380 files (24,858 of
  183,636 lines), the schema one 327 (175,141 lines) in 1.7 s, all 380 parse in 0.76 s, so
  resolving a member's types only at its lookup was not needed. The classic program lowers 713
  forms, 575,271 B of wasm, in 7.9 s.
- **Annotations** ("Annotations" below): a dependency's annotation whose type is on no root is
  dropped, as
  javac silently reads a class file whose annotation type is off the class path (jspecify is
  `provided` in YAVI's pom, not in the graph).
- **A class in the file of another class is found** (`JavaClasses.declaring`), as through the
  library's jar: on JDK 25 `lib.Uses` calling `Hidden` of `lib/Lists.java` is `cannot find symbol`
  under `javac -sourcepath src` and the source launcher with `lib/` in its tree, compiles and runs
  against the jar of the library compiled whole, and `lib.Hidden` named by a program against that
  jar is `Hidden is not public in lib`. So a ROOT is searched, the program's source tree not (the
  launcher's rule). Not `public` (JLS 7.6), such a class is in its package's directory, whose
  files under every root are searched once by keyword for the names they declare
  (`JavaParser.declaredNames`; a jar opened once a package, a zip per entry costing ~0.1 s); only
  a file spelling the name is lexed for a top-level declaration. Nearly every package reached is
  searched (each simple name is first a miss in its package: `am.ik.yavi.core.String`). Rejected:
  lexing every file (YAVI's 380, 8.8 MB: 354 ms cold), a scan by characters (305 ms); the keyword
  search takes 75 ms, leaving `Booking`'s 5.6 s lowering and the modules (`Booking`, `Member`) as
  they were.
- **Resources**: a sources jar holds them (maven-source-plugin packs `src/main/resources`: YAVI
  0.18.0's `META-INF/services/..Processor` and native-image `reflect-config.json`, netty's
  `META-INF/native-image/..`); "Resources" below.
- **What a library's body names that the subset lacks: no error until it runs** (2026-11-25,
  l86). A JVM links a class's code to what it names where that code first runs, and libraries
  count on it: pgjdbc guards optional JDK modules by catching `NoClassDefFoundError`, AssertJ
  names `java.text`, `java.lang.management` and Byte Buddy in code no check of l81's probes
  runs. So, in a DEPENDENCY's body only (not the program's files, not the library's own
  source, not during a census):
  - a class name nobody has is a name (`JavaClasses.require`, as in its declarations);
  - **no value is of such a class**: a type test is false, the operand still evaluated, a
    cast lets `null` alone through (`instanceTest`), a `catch` clause of one is dropped
    (`attempt`). JDK-faithful for a JDK class the subset lacks, which no value can be; for a
    class absent where the program runs a JVM throws `NoClassDefFoundError` at a non-null
    `instanceof` instead;
  - what asks what such a class is made of (`JavaClass.Absent`) or names a JDK class or member
    the library lacks (`JavaClasses.Missing`, `NO_CLASS` / `NO_METHOD`) makes the STATEMENT
    throw `NoClassDefFoundError` / `NoSuchMethodError` where it runs, the refusal's words its
    message (`JavaLowering.deferred`): a statement of `sequence` (the condition of a block's
    last `if` whose branch exits too, which lowers the rest inside it: missed, the whole
    method threw), a local's initializer, a field's (`initialValue`), a lambda's expression
    body (a library's `ThreadLocal.withInitial(() -> new SimpleDateFormat(..))` is no throw at
    class initialization), a method whose lowering refused outside its statements
    (`throwing`; a constructor's refusal stands). A class literal of such a class asks it,
    as a JVM's `ldc` loads it. Any other refusal is a gap of the front end and stands;
  - the throw is a visible effect: a static initialization that may throw one is LAZY
    ("Static initialization"). Without it AssertJ's `Assumptions`, with Byte Buddy on the
    path, initialized before `main` and threw, where a JDK never loads the class;
  - **the compilation warns of each statement** (`JavaWarnings.deferred`): `warning: <file>:
    <line>:<col>: <refusal>; where it runs, it throws java.lang.NoClassDefFoundError` and the
    `reached through` lines, after javac's warnings, each once, at most 100; a lowering that
    is dropped takes its own back (`Standing.deferred`);
  - **reflection over a member whose types name such a class** (a method listed or looked up
    by name, a field, a constructor: `JavaLowering.describedAbsent`) throws
    `NoClassDefFoundError` where the program asks for it, as a JDK's `getDeclaredMethods`
    does over a class whose member names one its class path lacks, and warns at the member.
    Trap: it was the `Absent` out of `generatedFunctions`, reported at whatever unit was last
    lowered (`library/java/io/StringWriter.java:1:1: the class java.text.SimpleDateFormat ..`):
    Commons Logging's `SimpleLog.getSimpleDateFormat()`, described because the repository's
    metadata names `SimpleLog`.
  Pinned: java-spec `a-library-s-code-naming-what-the-subset-lacks-throws-only-where-it-runs`
  (a JDK class the subset lacks in a type test, a pattern, a cast and a catch clause; a class
  the library was compiled against, absent where the program runs, thrown as under the JDK:
  `JavaSpecE2eTest` compiles a case's `provided:` apart, off the JDK leg's class path; a method
  the subset lacks never run), `JavaLoweringTest#aLibraryOfTheSourcePathIsLoweredAsFarAsTheProgramReaches`
  (the warning's words, the throwing function, the class withdrawn under another).
- **Rules of the language a library showed missing**, pinned by the fixture: an **exact method
  reference** naming a class counts in applicability by what its method returns (JLS 15.12.2.2,
  `JavaLowering.gives`: `Book::title` picks `constraint(ToCharSequence, ..)` among fifty overloads
  taking a function; an implicit lambda there is ambiguous under javac too); a **capture turned
  back into a wildcard keeps the bound it took from its parameter** (`JavaTypes.uncaptured`); a
  **permitted class in a file of its own** read during its `permits` clause is checked once the
  clause is whole; a simple name that is an imported class of a JDK's packages says
  `the class java.text.Normalizer`, not `cannot find symbol: variable Normalizer`. Found by
  AssertJ (l86): a **single-static import of a member of a class still being declared**
  (`Assertions` and `AbstractCharSequenceAssert` name each other; a program's two files too:
  javac takes it) is checked once the outermost declaration is whole, and a library's file is
  not asked, a compiler having taken it (`JavaClasses.importStatic`, the `waiting` checks;
  java-spec `a-program-of-several-files-finds-its-classes-by-package`, `Strings`/`Registry`);
  of **generic methods the most specific** is the one whose parameter types the other's type
  parameters, inferred from them, take within their bounds (JLS 18.5.4: `<T> k(T)` is not as
  specific as `<T extends Throwable> k(T)`, AssertJ's `assertThat` overloads; a method is not
  compared with itself; java-spec `an-overload-is-chosen-by-the-static-types-of-the-arguments`);
  an int **constant narrowed, then boxed** where it is assigned and fits (JLS 5.2: `Byte b = 1`,
  `return 0` from a method of `Short`; java-spec `a-box-is-its-value-but-a-long-s-and-none-is-null`).
- **YAVI's source is not in the repository**; the fixture is a library written here
  (`java-spec.yaml` `a-program-uses-a-library-read-as-source-from-its-source-path`: `library:` is
  zipped to a sources jar for the four legs and compiled by javac for the JDK leg, `provided:` is
  what javac alone reads).
- **The second library: the Jakarta Servlet API 6.1 with the container of
  `servlet-httpserver/`** (`.kb/servlet-httpserver.md`, "What the rontolisp side needs"): one
  servlet reaches 61 functions of the API. The served corpus reads the API's sources jar through
  `am.ik.maven`, skipping where it cannot be had.

## Resources (2026-11-20, k53; a computed name, bundles, services: k58; URLs, a computed service: k69; directories, the playground's uploads, a bundle class only a computed name finds: k75; a computed name's constant prefix and suffix: l40)

**Invariant: a resource is found while the program is lowered, by the loader a source
launcher would give the class, and its octets travel in the output: a constant name's one
file where the call is, and for a name computed where the program runs every file its
loader may find, in one table the program carries (`JavaResourceTable`).** What a program
sees: `doc/en/java/index.md`, "Resources"; the differences: `deviations.md`. Read off JDK
25.0.5's `java -cp libcls app/Main.java`: with no `-cp` the default `.` is often the tree's
top, whose directory rule then answers (`../top.txt` found).

- **Two loaders**, the launcher's (`JavaLowering.loader`), measured: the program's finds a
  tree `lib/label.txt` before the class path's; a JDK class finds none
  (`String.class.getResourceAsStream("/top.txt")` is null); a loader finds no name starting
  `/`, nor a `..` out of a directory root; `getContextClassLoader()` is the program's, not
  the system's.
- **Lowering.** A class literal's lookup is answered where written (no class object made);
  any other receiver is told where the program runs among the classes under its bound
  (`byClass`), so a library's `getClass().getResourceAsStream("note.txt")` reads the
  SUBCLASS's package, as a JDK does. **The octets** travel as a text of one character an
  octet (`Resources.stream`), not UTF-8: one road for every file. A 200,256-octet resource
  adds 200,155 B of wasm if ASCII, 300,160 if random (a character above 127 is two in the
  string data), read back hash-identical on the four backends, NUL included; a program
  naming none is byte-identical.
- **Roots.** The tree is the entry's or the playground's uploads (an upload's octets its
  text's UTF-8), none for `-e`. The Maven plugin's `wasm` goal passes
  `${project.build.outputDirectory}` (`classesDirectory`) as the FIRST root, ahead of
  `<javaSourcePath>` and the sources jars: javac's class path order, holding the resources
  as the resources plugin left them (filtering, `targetPath`), which `src/main/resources`
  read in place would not; left out where it does not exist (before `compile`). Staleness
  (`WasmProgram`) covers every file of the tree and of a directory root; pinned in
  `MavenBuildE2eTest` (a filtered `app.properties`, changed, rebuilt).
- **A computed name: carried, not read where the program runs.** In a local repository's
  753 libraries (the k58 artefacts' `Scan.java`) 129 of 278 `Class.getResource*` calls name
  a constant, 69 a concatenation, 55 a parameter; 147 of 396 loader lookups and 31 of 110
  `ServiceLoader.load` calls a parameter; a parameter or a field bounds nothing. Rejected:
  reading the roots where the program runs (the Clojure front end's way, which it keeps: a
  Ring `:root` serves any request path), which a `.wasm` with no file system, a served
  component and a `.class` moved off the compiling machine cannot.
  - **What is carried** (`JavaClasses.carry`): every file of every root, and of the tree
    where the program's loader may be asked, in name order (not the host's), but those the
    user's page leaves out (a constant name still finds a `.java` file); past its bounds,
    `unsupported`: `a resource named where the program runs while the source tree holds
    more than 10000 files to carry` (a tree whose top is a home directory).
  - **A constant prefix and suffix bound it** (`affixes`: the constant text every value of a
    concatenation starts and ends with, however it nests); `concat`, a parameter or a local
    holding the concatenation bound nothing, and an empty prefix bounds no prefix (the name
    may start with `/`). Under a directory root only the suffix's last segment bounds: it
    resolves a `..` the computed part may hold (`"t/" + "../b" + ".txt"` finds `b.txt`, JDK
    measured). A session carries every file (its table is read once). A computed
    `getBundle` base name with no `Control` or module carries the `.properties` files under
    its prefix with `.` made `/` (the run time reads nothing else), and every bundle class.
    Measured (component, 2026-11-23): the WebMvc.fn page over
    `examples/java/mustache`'s templates is 1,405,976 B by `"templates/" + name +
    ".mustache"`, 1,405,861 by constant names, 3,243,344 by the `concat` form, which carries
    every file; the Java shiori's `web/Templates.java` computes its names so.
  - **The table** is one text the run time walks (`ResourceTable.find`). Rejected: maps
    parsed in a static initializer (`HashMap`, `ArrayList`, `Integer.parseInt`), 194,314 B
    of wasm for a program computing one name against 50,285 walking the text (43,195 by a
    constant name). A program computing no name has no `ResourceTable`.
- **`ResourceBundle`**, probed: a library's `getBundle` asks the system's loader, which
  misses the tree; one bundle a loader, name and locale (`getBundle(US)` and
  `getBundle(CANADA)` one object); a `PropertyResourceBundle`'s keys a `HashMap` copied from
  its `Properties`, a `ListResourceBundle`'s one of `contents.length` (3 keys in 4 buckets,
  not 16), `getKeys` its own then its parents' it lacks, `keySet` a `HashSet`; a missing
  key refused with the LAST class's name. The words: `Can't find bundle for base name B,
  locale L` (class name `B_L`, key `""`), `Can't find resource for bundle C, key K`. A
  session's `getBundle` carries every file: a later buffer may name any bundle.
- **`Properties`**: the orders (a `ConcurrentHashMap`'s keys of the capacity given,
  `propertyNames()` a `Hashtable`'s, `stringPropertyNames()` / `list` a `HashMap`'s, filled
  from the deepest defaults up), probed: 40 keys under six capacities, 300 under none, each
  candidate simulated over 40 keys. `load` / `store` are the documented grammar (the
  corpus's 53 lines), `store` by sorted key (JDK 18 on); a string's element here is a code
  point, so `load` joins an escaped pair into one.
- **`ServiceLoader`** resolves the providers where the program is compiled
  (`JavaLowering.services`); where each failure shows (an iterator's `next`, `hasNext`
  true; a stream's advance or `get`), a new instance every `get` and the two caches apart
  are a JDK's answers; `loadInstalled` and the platform loader find none. `provider` makes
  `Supplier` a lambda target (the maker is a raw lambda held as one), else a program whose
  only `Supplier` is a provider's fails in `get` (`get() has no implementation for its
  receiver`; pinned by `a-program-of-several-files-finds-its-classes-by-package`). A
  `Class<?>`'s service is told among those whose class the program has (`byService`): a
  `Class` object it holds is of a class it has. An interface is under `Object`
  (`isSubclassOf` answers false for it, which the bound check must not ask).
- **The JDK leg's default locale is `en_US`**: `JavaSpecE2eTest.runOnJdk` passes
  `-Duser.language=en -Duser.country=US`, the subset's `Locale.getDefault()`, so a line
  reaching the default locale (a bundle's fallback) is not the host's.
- **URLs**, read off JDK 25.0.5's launcher: the
  authority `""` (printed `file:/..`), a jar's `null`, the port -1. The escapes differ by
  place (every ASCII character probed): the tree escapes space `" # % < > ? [ \ ] ^ `` ` ``
  `{ | }` and past ASCII its UTF-8, in capitals; a class path root `;` and `=` too, in small
  letters, and past U+FFFF each surrogate's three octets (U+1F600 `%ed%a0%bd%ed%b8%80`).
  Not carried over: a class path root finds no name holding `:`, the subset's does.
  - **The URL carries the octets** (`URL.located`); any other URL reads nothing, one made
    again from a resource's text included -- a table of URLs to octets would carry every
    constant read twice for a round trip a program rarely makes. The places' paths and the
    URLs' walk come only with a URL of a computed name (one walk for both: a program
    reading a computed name 47,499 -> 50,600 B).
  - **`java.net.URL`**: the API documentation and 98 probe lines (corpus
    `a-url-is-cut-into-its-parts-as-a-jdk-cuts-it`) -- a JDK 25's protocols (`file ftp http
    https jar jmod jrt mailto`), refusals and parts; `equals` / `sameFile` compare the
    host's text ignoring case, not the user; `hashCode` our own; `URI.toURL` of a relative
    URI `URI is not absolute`. The checks are ASCII: `Character.isLetter` /
    `toLowerCase` pull the Unicode tables, 56 KB of wasm for a program asking one
    resource's URL.
- **A member class as a bundle** (`app.Outer$Texts`), probed: a public static one with a
  public constructor of no parameters; a non-public or inner one skipped, `app.Outer.Texts`
  none.
- **A directory**, read off JDK 25.0.5's launcher
  (the four backends print its every
  line but the listing's order): its stream lists `.java`, `.class` and dot names too; in
  the tree the top `""` is none (the loader's `""` is the first class path directory's), as
  is `sub/a.txt/`. Under a class path directory the URL keeps the slash the name ends in or
  a last `.` or `..` leaves, a FILE's too (`app/sub/l.txt/` finds the file); a name with no
  `..` is resolved by the file system (`a.txt/.` none: ENOTDIR), one with a `..` made
  canonical first (`a.txt/..` the directory); a `//` kept there (`lone//x`) is not carried
  over. A jar's directory, only where the jar holds its entry, reads as empty.
  Pinned by `JavaClassPathCliTest.aJavaProgramCompilesItsLibrariesFromTheSourcePath`, not
  `java-spec.yaml`, whose library is a jar on the subset's side and a directory for the JDK
  leg (`a.txt/..` differs there by design). A listing's order is the launcher's default
  locale's collation (`_u`, `.hidden`, `10`, `9`, `a.txt`, `Alpha`, `b.txt`); here the
  names' characters, fixed when compiled. A computed name carries a directory's listing:
  ~18 KB of names over 2,003 classes in 41 directories.
- **A bundle class only a computed base name finds**: the launcher compiles the class
  `getBundle` asks for, which nothing else named. `JavaClasses.declareBundleClasses`
  declares the tree's and the roots' classes that extend a bundle class by its simple name
  (a fixed point), parsing only a file whose text holds one of the names. Rejected:
  declaring every file -- a class of the program is lowered whole, so an unused file with a
  type error would refuse a program the launcher runs (probed: `Unused.java`). A
  2,003-class tree: 5.9 -> 6.0 s.
- **`ResourceBundle.Control`**, read off JDK 25.0.5 (pinned by the corpus's `controls()`,
  which logs every call): `getFormats` once, then for each locale from the target through
  `getFallbackLocale`: `getCandidateLocales`, the candidates from the LEAST specific up,
  each `newBundle` of each format until one is made, then `getTimeToLive`; a base bundle
  found earlier stands for a later locale's root; it stops at a bundle that is not the base,
  is of the locale asked, or of the only candidate. A kept bundle is not asked for again,
  whatever the `Control`; `getBundle` sets the name and locale of a bundle `newBundle`
  made; an exception `newBundle` threw is the refusal's cause; `getControl` /
  `getNoFallbackControl` answer one object a format list (another list:
  `IllegalArgumentException`); an unknown format `unknown format: x`. Not copied: a time to
  live other than `TTL_DONT_CACHE` keeps the bundle for good (`needsReload` never asked); a
  JDK's helpful `NullPointerException` texts. With no `Control` the walk runs on static
  defaults, holding no `Control` and no `catch`: 255,430 B of wasm, 268,514 with
  `Control.INSTANCE` as the default. A `Control` or a module carries every file and bundle
  class: a `Control` may name any (`toResourceName`, `toBundleName`).
- **`Class.getModule()`** is a native: as `ClassLoader.moduleOf(getClassLoader())` in
  `Class` it answered `java.base` for a program class, `byClass` telling a
  `getClassLoader()` in a body of `Class` over the type variable (not looked into).
  `getBundle` of `java.base` refuses (probed).
- **`Properties.storeToXML` / `loadFromXML`**: the API documentation, XML 1.0 and the
  properties DTD, every answer a JDK's (98 probe lines; the corpus's `properties()`): no
  `<comment>` for an EMPTY one, the entries in the table's order (not sorted, as `store`'s),
  what the charset lacks, past the first plane or a lone surrogate as `&#x..;` (UTF-8 too),
  a key or value no `String` refused before a byte is written (`ClassCastException: Keys and
  values in Properties must be Strings`), the stream flushed, not closed; the reader
  (`rontolisp.internal.PropertiesXml`) as lenient as a JDK's where the DTD is not (text
  outside an entry dropped, an entry inside an entry starting a new one, an entity no
  declaration names nothing in text, `&#0;` a NUL); refusals in the JDK's words after
  `jdk.internal.org.xml.sax.SAXParseException; ` (nothing after it where not well formed,
  as a JDK's parser says nothing there) and with no cause (a JDK's is its parser's
  exception).
- **Left** (`.todo/k95`, its locale bullet): the candidate locales of a script and a variant
  and the Norwegian and Chinese cases `Control`'s documentation states, once `Locale` has
  such locales.
- **Beside it**: a qualified name of a JDK package naming no class the subset has, read as a
  value (`java.util.ResourceBundle.getBundle("m")`), is refused as `the class
  java.util.ResourceBundle`, not `cannot find symbol: variable java`
  (`JavaClasses.namesPlatformClass`).
- Pinned by java-spec `a-program-uses-a-library-read-as-source-from-its-source-path`
  (`resources()`, its JDK leg's library classes carrying the `library:` entries that are no
  `.java`; `computed()`, `bundles()`, `services()`, `urls()`, `directories()`,
  `controls()`), `a-deque-an-optional-the-utilities-and-the-older-classes` (`properties()`),
  `JavaLoweringTest#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition` (the
  qualified JDK classes), `JavaLoweringTest#aProgramOfSeveralFilesIsReadFromItsSourceTree`
  (more files than a program carries; a bounded name there carries what it may find),
  `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram` (an
  upload by a constant and a computed name),
  `PlaygroundReplTest#aClojureRequireReadsTheUploadedDepsEdnLikeEveryOtherRoute` (the
  uploads' directories to Clojure and Common Lisp).

## YAVI compiled to wasm (2026-11-20, k33)

**A program over YAVI (`am.ik.yavi:yavi:0.18.1`, Apache-2.0) compiles from the unpatched
coordinates and prints a JDK's lines on the interpreter, the JVM, wasm and the component,
in both of the library's styles** (`ValidatorBuilder`; the schema, `Yavi.schema()` ..
`fromMap`).

- **Reversible schemas** (`Yavi.reversibleSchema()`) need the upper bound of an argument's
  call, `this::name` ("Inference") and `java.util.IdentityHashMap` (YAVI's `PropertyView`
  keeps the descriptions it has seen in a `Collections.newSetFromMap` of one): java-spec
  `a-type-argument-is-found-from-the-arguments-the-target-and-the-lambdas`,
  `a-hash-map-finds-a-key-by-equals-and-keeps-the-order-a-program-sees`; used by
  `examples/java/shiori/src/main/java/com/example/shiori/book/BookDetails.java`.
- **The examples**, checked in `examples.yaml` on the four:
  `examples/java/yavi/src/main/java/yavi/Member.java` (the classic style, 639,061 B of wasm)
  and `examples/java/yavi/src/main/java/yavi/Booking.java` (a schema, 983,445), measured
  2026-11-22; YAVI is 6 % and 16 % of them, only what is called being lowered
  (`-Drontolisp.wasm.debug-func-sizes`).
  The runtime and **the regex engine, in every YAVI program** (130 KB, `regex-engine.md`,
  "Measured"), are 48 % of `Member`: a string constraint reaches `CharSequenceConstraint`'s
  three static patterns and `VariantOptions`' `replaceAll`; without the patterns the
  classic example is only 937 B smaller.
- **Split in YAVI by https://github.com/making/yavi/pull/604** (released as 0.18.1): 0.18.0's
  `core/UntypedInput` held every type YAVI reads an input as in one table and one method, so
  a schema paid for all (one of a string and an integer 1,144,352 B; -16.9 % without the
  types read from their text, the `Date` / `java.sql` arm and the `Year` arm, -22.3 %
  without the conversion through `BigDecimal` too). Added public API, nothing changed:
  `UntypedConversions` (one conversion a type) and `ValueValidator.untyped(Class,
  Function)` for the callers that know the type (`builder`, `arguments`). Measured
  2026-11-22: that schema -13.8 % (900,588 B), `Booking` -10.8 %, `Member` unchanged;
  `java.math` stays (21 KB: an integer may be a `BigDecimal` or a `double`). YAVI's
  `scripts/generate-args.sh` runs to its end only under bash 3.2 (5.1 and 5.2 stop early
  without failing) and needs `perl`.
- **A variable named `set` put the eval runtime in `Booking`**: both backends' gate counted
  any mention of `set`, `symbol-value` or `fboundp`, and `eval/uri.lisp` has a parameter
  named `set` (on wasm `_eval`, `_apply`, `_dispatch_spread`: 53,761 B). The gate asks
  whether the name is called, taken by `#'` or quoted, or a place written
  (`compiler/FunctionNameUses`).

## pgjdbc measured (2026-11-22, l01)

**pgjdbc 42.7.14 (`org.postgresql:postgresql`, BSD-2) does not compile from its sources jar,
and nothing of it runs here yet; this is the measurement the choice between it and a JDBC
driver of our own waited on. The user chose the driver of our own (2026-11-22):
`jdbc-postgresql/`, [jdbc-postgresql.md](jdbc-postgresql.md), whose numbers stand beside
these. pgjdbc itself is the long-term `.todo/l22`.**

- **The inputs.** The sources jar (296 files, 70,780 lines) lacks the SCRAM library the
  binary jar shades: `--java-source-dep com.ongres.scram:scram-client:3.2` beside it. The
  probes: `Hello` (a login, `select 42, 'hello'`) and `Ladder` (what "done" asks: the four
  logins, CRUD, a prepared statement run twice, non-ASCII text; the JDK's 17 lines in
  `probes/Ladder.out`).
- **Named and missing.** The sources import 330 names of a JDK's packages and spell 63 of
  `java.lang`'s; **the library lacks 145 of those classes**, and four more are no better
  (`Calendar` and `Logger`, names only; `Locale.Category`, `ForkJoinPool.ManagedBlocker`):
  the 149 of `census/classes-the-library-lacks.txt`.
- **Refused** (`Ladder`, statement by statement, `census/ladder-no-shells.txt`): 197 over
  1,173 methods reached, `java.util.logging.Level` 133 and `Logger.getLogger` 20 (79 now:
  "pgjdbc: what a connection meets that no other item makes").
- **Once every class is there** (shells of the 147 that can have one and of what their
  signatures name, 307 classes; `census/ladder-with-shells.txt`): `Ladder` reaches 1,569
  methods of the libraries in 178 files and 1,862 of the library's own, names **136 members
  of 48 of the missing classes** (`census/shell-members-named.txt`) and is refused 36 times
  more. The families, what was left to make:
  - Made since: **Logging**, **Reflection** ("Reflection in full", "Reflection from
    reachability metadata", "java.lang.reflect.Array and java.lang.invoke"), **References**
    ("References and the weak map"), **Buffers** and the charset coders ("The buffers", "The
    charset coders"). Two reflection sites run for every connection: `Driver.doPrivileged`
    asks `java.security.AccessController` by name, `TimestampUtils`' static initializer a
    private field of `TimeZone`.
  - **The calendar**: `Calendar` (13 members), `GregorianCalendar` (6), `TimeZone` (7),
    `SimpleTimeZone` (6), which `TimestampUtils` makes where a connection is made.
  - **TLS, certificates, names**: `SSLSocketFactory.createSocket(Socket, String, int,
    boolean)`, `SSLSocket` (5), `SSLParameters`, `SSLSession`, `HostnameVerifier`,
    `Certificate.getEncoded`, `X509Certificate` (3), `X500Principal`, `LdapName` (6), `Rdn`
    (2) (`.todo/l15`); **GSS and JAAS**: `GSSManager` (6), `GSSContext` (8), `Oid`,
    `MessageProp`, `LoginContext` (3), `Subject` (2). None runs in the four logins; all are
    reached.
  - **The rest**: `SQLPermission` (two static fields of `PgConnection`), `NumberFormat`
    (the server's version), `URLDecoder`, `IDN`, `ManagementFactory`, `System.getenv()` (the
    service and password files), what the next section made, `java.sql`'s ("What l00 left
    of java.sql").
- **What a JDK loads** for the four logins and the CRUD (`-Xlog:class+load`), so what has to
  work and not only be there: logging, the calendar's four, `NumberFormat`, `URLDecoder`,
  the references, `SQLPermission` and its superclasses, `ClassNotFoundException`,
  `Constructor`, `Field`, `Array`, the buffers, the charset coders, `Locale.Category`.
  Reached and never loaded: `LogRecord`, TLS, certificates, names, JAAS, GSS,
  `ManagementFactory`, `IDN`.
- **pgjdbc starts threads** (a cleaner's, `LazyCleanerImpl`; a timer's, `SharedTimer`; a
  connecting thread under a login time limit, `Driver`), so on one stack every function
  above a socket read is a resumable body ("Threads").
- **Sizes: a lower bound** (`Hello`; the census's `go` build over the shells, the 36 refused
  statements left out, every body of a shell a throw): wasm 7,800,714 B (`--optimize` the
  same), 50-53 s to compile; the component 7,877,748 B, 52 s; the JVM jar 1,743,439 B, 43
  s. Of the wasm's 7,242,668 B of functions the resumable bodies are 3,218,081 and **71
  lambdas of some 26 KB each 1,844,236** -- one shape, a test of one argument's class
  against some 31 classes, each arm an indexed read and a throw; what makes them was not
  found. The jar runs to `No suitable driver found`: `Driver`'s static initializer ends at
  the first shell.
- **A driver of our own**, estimated: none of the families above needed, 2,500-3,500 lines,
  **0.6-0.9 MB of wasm** (a tenth of pgjdbc's lower bound), no thread. **Made (2026-11-22):
  0.78 MB a component for `Hello`, 0.81 MB for `Ladder` -- the size held, the lines did
  not: 5,500 with the JDBC surface past "done", and 2,400 of refusals written by a script**.
- **Made here, rules of the language** (java-spec
  `a-boolean-operator-short-circuits-or-evaluates-both`, the library of
  `a-program-uses-a-library-read-as-source-from-its-source-path`, `refused:`
  `an-assertion-of-no-boolean`): **`assert`** (JLS 14.10) is never enabled
  (`deviations.md`) and emits nothing. A program's own is typed (the condition a `boolean`,
  the detail no `void`), a library's not -- its compiler typed it, and typing it would reach
  what it names; its flow is JLS 16.2.8's, and a checked exception it throws has to be
  caught. **An annotation ahead of a variable arity parameter's `...`** (`String
  @Nullable ... names`) is dropped as one of a type is ("Annotations").
- **If pgjdbc is taken up** (`.todo/l22`, its first part below): what a library's body does
  where it names what the subset lacks and never runs (TLS, GSS, the XML types) is to be
  decided -- today a refusal where the program is compiled, which no program over pgjdbc
  passes; then the 71 lambdas.

## pgjdbc: what a connection meets that no other item makes (2026-11-25, l22)

**`.todo/l22`'s first part: the refusals of `Ladder` (the census, "pgjdbc measured") that no
other item owns, made; the census taken again.** Each is JDK 25's answer on the four
backends (java-spec); every program naming none of it is byte-identical (the size programs
of `.todo/artefacts/l22-pgjdbc-on-rontolisp/`, `examples/java/shop` and `Ladder` over
`jdbc-postgresql/` among them).

- **The census** (`census/` of the artefacts, l01's tools, `StubGen` not forcing `Logger`):
  with no shells 79 refusals (94 at this item's start, 197 in "pgjdbc measured"); once
  every class is there 7 (19, 36); the members of the missing classes named 116 (90, 136)
  -- more, since the bodies behind a refused receiver parameter and the SCRAM table now
  lower and reach the TLS key managers (`Files`' attribute members), the certificate
  factory and `SSLContext`. Of the 79: `Calendar` and its zones 63
  (`.todo/l79`), `Array` 6 and `MethodHandle` 2 (since made: "java.lang.reflect.Array and
  java.lang.invoke"), `System.getenv()` 3, `NumberFormat` 1 (`.todo/k87`), and 4 in bodies
  that never run in the four logins (TLS, JAAS, `ManagementFactory`).
- **`StringBuilder` / `StringBuffer`**: `insert` of a range of a text and of a `char[]`,
  `getChars`, `ensureCapacity` (nothing: no capacity a program can tell), with JDK 25's
  checks and words: the index first (`StringIndexOutOfBoundsException`, `Range [at,
  length)`), then the text's range (`IndexOutOfBoundsException`) or the array's
  (`StringIndexOutOfBoundsException`); `getChars` the builder's range, then the array's
  (`IndexOutOfBoundsException`).
- **`Boolean.getBoolean`, `Integer.getInteger`, `Long.getLong`**: no property
  `Support.property` answers is `true` or a number, so a constant name is answered where the
  program is compiled (pgjdbc's `org.postgresql.forceBinary`), a computed one where it runs.
- **`Thread.setContextClassLoader`**: the loader is an `InheritableThreadLocal`'s value, so
  a thread made takes its maker's, as a JDK's does; where nothing sets one
  (`Thread.contextSet()`, answered by reaching the setter) `getContextClassLoader` stays the
  program's loader. The local is a holder class's static field: one of `Thread` costs 0 B
  on wasm but 46 B in every JVM jar with a thread (the JVM backend initializes every static
  field of a class it lowers), an instance field 109 B in every wasm program with a thread.
  Setting one costs +2,492 B beside a `HashMap` and an exception (alone +64 KB: the map the
  local's values are).
- **`Locale.Category`**: `Locale.getDefault(Category)` is `US`, as `getDefault()` is;
  `setDefault` stays refused. **`ForkJoinPool.ManagedBlocker`**: `managedBlock` is the
  blocker's own wait, `isReleasable` asked before each `block` (a JDK's counts: 3/2 and 5/4
  in the corpus); a pool here makes no room for a wait, making one more thread whenever none
  is free anyway.
- **The receiver parameter** (JLS 8.4, `LibPQFactory`): `C this` / `C Outer.this` first in
  a method's or a constructor's list, parsed and dropped, checked with javac's words and
  columns (`JavaClasses.receiver`): a static method or a static class's constructor, a
  top-level class's constructor, the type and the name of an instance method's (the class,
  its own type parameters) and of an inner class's constructor's (the class around it), a
  name that encloses nothing, then the parser's three (not first, `final`, variable arity).
- **A field updater's class literal behind casts** (`QueryExecutorCloseAction`'s
  `(Class<@Nullable PGStream>) PGStream.class`): seen through, each cast typed as anywhere
  else.
- **`collectingAndThen(toMap(M::getName, Function.identity()),
  Collections::unmodifiableMap)`** (`ScramMechanism`; "Inference", the tie through `? super
  T`): `toMap`'s `U`, found by nothing, stayed open and the finisher grounded it to `Object`
  (`inference variable R has incompatible bounds`).
- **Left**: `System.getenv()`, the map (`.todo/l22`); `toMap(identity(), identity())` inside
  `collectingAndThen`, whose `T` is the stream's alone (a joint inference over the nested
  calls, which the front end does not make: "Inference").

## A census of a library (2026-11-22, l01)

**`-Drontolisp.java.census=FILE` takes a measurement instead of making a program: every
refusal a program reaches in the libraries of its source path, not the first.** A debugging
switch of the JVM running the compiler, no user surface (`JavaCensus`); without it the
outputs are byte-identical.

- **Statement by statement**: a refusing statement of a DEPENDENCY's body is noted and left
  out (`JavaLowering.counted`), a local whose initializer refuses still declared when its
  type is written (`var`: the rest of its block left out). Without the census such a
  statement naming what the subset lacks throws where it runs ("A library from the source
  path"); a class name nobody has refuses only where what it is made of is asked. So what a
  refused statement would have reached is NOT in the census, and a later statement may
  refuse for a name the
  first would have declared. The program's own files refuse as ever. The compilation ends
  with `N refusals reached, written to FILE`; the file's `NAMED` lines are the members of a
  SHELL the lowered calls name.
- **A class the library lacks hides what is behind it**, so a census is taken over shells: a
  file under `am/ik/rontolisp/java/library/` on the class path AFTER the compiler's classes,
  starting with `// STUB` -- a JDK class's names and signatures, every body a throw
  (`StubGen` of the l01 artefacts writes them by reflection). Measurement only: a shell is
  never shipped ([java-frontend-provenance.md](java-frontend-provenance.md)); a class the
  library has as a name only (`Calendar`) has to be forced. An instance method of a class
  nothing makes is not lowered, so the members are counted where they are named.
- **`-Drontolisp.java.census.go=true`** compiles what is left, for a lower bound of a size;
  such an output is not the program (its refused statements are gone).

## jmustache and reflection by name (2026-11-22, l37)

**jmustache 1.16 (`com.samskivert:jmustache`, BSD-3-Clause) compiles from its unpatched sources
jar and prints a JDK's lines (JDK 25.0.5, wasmtime 51.0.0) on the interpreter, the JVM, wasm, the
component and `--native`; its refusals were all reflection in `DefaultCollector`.** Nothing of
jmustache is in the repository; the probes were not kept ("A census of a library").

- **Trap: `Map` contexts keep that reflection from running, not from being reached**: a call
  through `Collector` reaches every class the program makes, and neither `withCollector` nor
  `Compiler` (its constructor `protected`) avoids `Mustache.compiler()`'s `DefaultCollector`.
- **Decided: it is written into the front end** (k89 covers it anyway). jmustache asks every value
  a name is looked up in, a `String` or an `Integer` too, for `getDeclaredMethod(name)`,
  `get`+Name, `is`+Name, `getDeclaredField(name)` up the classes, catching every `Exception`, then
  for `getInterfaces()` outside any `try` (an array type answers `Cloneable` and `Serializable`).
- **Lookups by name** (`%class-method` / `%class-field`): a program class's methods of no
  parameter and fields, the implicit ones included, with modifiers read off a JDK (an interface's
  abstract method 1025, a record's `toString` 17, an enum's constant 0x4019). A JDK class answers
  by l64's table, a library's by m03's metadata (below), any other is refused
  (`UnsupportedOperationException`), which jmustache takes for a member not found. Rejected: every
  class with a source -- a served program then reached every method of no parameter of the
  container's and the libraries' classes, and the hello world grew 508,886 -> 571,241 B.
- **`o.getClass().isArray()` is an array test**: `BasicCollector.toIterator` asks it before
  testing for a `Mustache.Lambda`, which has no class object; `null` raises.
- **`split` of an escaped character skips the regex engine** (`JavaIntrinsics.plainText`):
  jmustache's `name.split("\\.")` carried it, the hello world 723,910 -> 571,241 B of wasm.
- **`Method.Found`** holds a found method's data in one slot: flat, the guestbook grew 2,427 B;
  with it 811 B.
- **Templates in a served component** (`servlet-httpserver`, `wasmtime serve`): as resources they
  are served; from a preopened directory (`Files`) **not**: the service world has no file system,
  the serve bridge answers file functions with errno 76 ([wasi-component.md](wasi-component.md)),
  so the read is `NoSuchFileException`.
- **Pinned by** java-spec `a-class-is-a-value` (`members()`: each answer above as a JDK's, access
  refused from a second top-level class) and `examples.yaml`'s
  `java/mustache/src/main/java/Shelf.java` (its pom's jmustache).

## Reflection in full (2026-11-23, k89)

**Invariant: a class of the program answers its members as a JDK's reflection does -- every
method, field and constructor it declares, the public ones it has, each with its types, modifiers
and text, found by name and types, called, made, read and written with the arguments converted
and the refusals in a JDK's order and words -- from tables the lowering makes over its class
objects; a program pays for a table, a call of parameters and a lookup by types only where it
asks for one.** Written from the API documentation (the members of `Class` and
`java.lang.reflect`'s classes; `getMethods`' published algorithm; the constants' values) and JDK
25's answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **Paid only where asked**: a class's tables (`%class-members`) are made for a list, a lookup by
  types, or `getDeclaredMethods` asked more than names (`IN_FULL`); not for a member's text, which
  any `toString` call reaches (a guestbook asking names alone: +88 KB). A method of parameters is
  called only where a call gives arguments (`JavaClasses.reflectsArguments`), else holds its
  class's instance test, asked before the count refuses; a session asks all. A lookup by types and
  the conversion of arguments are lowered only where a call gives types or arguments, behind
  natives the backends fold (`reflectsTypes`; the guestbook 1.6 KB): `Class.isInstance`'s tests
  would have every class object carry one, which, measured, tagged `Supplier`'s lambdas in the
  library case and broke its providers.
- **Ten arguments at most** (`LambdaLists.MAX_PHYSICAL_PARAMS`; past ten a list is built at every
  call: a factory of twelve made the shiori's `%class-method` +40 KB): the lowering's bits ride on
  the modifiers above the 16 a JDK uses (`Method.DEFAULT`, `VISIBLE`, `OF_ENUM`;
  `Constructor.ABSTRACT`, `ENUM`, `INNER`), and a member's functions take two arguments (a third,
  with a vector made for none at each entry: the shiori +118 KB).
- **Calls and fields as the probes answered**: arguments unwrapped and widened (JLS 5.1.2: a `char`
  to `int`, `long`, `float`, `double`; a `short` from a `byte` alone); the refusals in the order
  `Order.java` read off a JDK (the object `null`, the access, then the object's class, the count,
  the arguments; a constructor's enum, abstract class, count, arguments, inner class's `null`
  instance; a typed field access's type first) and in its words, e.g. `Can not set final int
  field X.f to java.lang.String` (naming the object's class), an `InvocationTargetException` of
  the `NullPointerException` an inner class's compiler checks. A final field is set after
  `setAccessible(true)`, never a static one nor a record's. A method of a variable arity has
  `TRANSIENT`'s bit (128); no `strictfp` (no class file of today's has it). `Class.newInstance()`
  throws what the constructor throws as it is.
- **The members the language declares and every modifier as `Members.java` read them off a JDK**
  (`%class-modifiers`): e.g. an enum's constructor private, taking the constant's name and ordinal
  first (the front end's two hidden parameters); an enum's constant `public static final` and
  0x4000; a primitive type and its array 1041, an array of a class `final abstract` and its
  element's access.
- **`getMethods`** (the API's algorithm): per signature as the class sees it the most specific, so
  a method implementing a generic interface's hides it as a JDK's bridge does
  (`Supplier<String>.get()`); not listed (`deviations.md`): the bridge itself, nor a JDK's
  visibility bridge in a public class under one that is not (`Members.java`: `Square.compareTo`
  under a JDK, `Base.compareTo` here). Which of `Object`'s and `Enum`'s methods run: l64's
  `ALWAYS_HELD`.
- **A member's text is lowered only where one can be made in full** (`Method.full0()`): a servlet
  program asking names alone (`HttpServlet.doOptions`) keeps none (the guestbook 2.1 KB).
  `Modifier.toString` reads its words off one string (two arrays of twelve: 2.7 KB of every
  program reaching an access check; the string 0.5 KB).
- **Measured** (2026-11-23): the shop, both YAVI examples, httpbin, mvcfn byte-identical; the
  guestbook +0.6%, the shiori +0.3%.
- **`java.lang.annotation`, `java.lang.ref`, `java.lang.reflect` are in `LIBRARY_PACKAGES`**: a
  compact source file and `import module java.base` name their classes with no import, as a JDK's
  do (no simple name of theirs is in another listed package). Pin: java-spec
  `a-compact-source-file-starts-at-its-instance-main`.
- **Not here**: the JDK's classes, a library's, `Array` and `java.lang.invoke`, generic types and
  annotations: the sections below.
- Pins: java-spec `a-class-is-a-value` (`general()`: every kind of member listed and looked up,
  the texts, calls and refusals, a class's modifiers), the probes on four backends (only a
  `NullPointerException`'s words, the visibility bridge and the constant read again differ from a
  JDK).

## Reflection over the JDK's classes (2026-11-25, l64)

**Invariant: a class of the JDK whose class object the program holds answers its public and
protected members as a JDK 25's reflection lists them -- names, types, modifiers, exceptions,
bridges, `getMethods`, `getFields`, the class's modifiers -- from a table read off a running JDK,
and runs one only where the compiled program holds what it runs: reflection adds nothing to the
program; a program reaching no reflection over a class of the JDK is byte-identical.** Written
from the API documentation (`getMethods`' and `getFields`' published algorithms,
`AccessibleObject`'s access rules) and JDK 25's answers to probes. Nobody opened
OpenJDK's source ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **The table** (`jdk-members.txt`, generated and pinned by `JavaJdkMembersTest`): 738 listed
  classes, 65 named, 612 KB (70 KB deflated in the jar; no program carries it). `getMethods()` and
  `getFields()` are not stored: `JavaJdkMembers` gathers them by the published algorithm, which
  the test holds to the JDK's answers for every class of the library, bridges and inherited
  members included. **A class added to the library changes the table**: the test fails naming the
  regenerated `target/jdk-members.txt` to copy over the shipped one.
- **Which classes answer** (`held`): those `deviations.md` lists; only for them is the table read
  (130 ms of a cold JVM). **A lookup by a name alone answers `named`, not `held`: an interface the
  program reaches only through `getInterfaces()` is refused** as a class whose members are not kept
  (measured, decided, m18): jmustache walks the interfaces of every value's class, and describing
  them -- their members' calls reach every implementation's body -- cost the shiori 77 KB, mustache
  2.5 KB; what is lost is a default method a template names on an interface (`{{stream}}`). The full
  tables (`getMethods` ..) still answer `held`. A class object only a member's type gives answers names and kind, no
  members: else the classes its members name would answer theirs, through the whole JDK. `Class`
  and `java.lang.reflect`'s classes answer none (`isReflection`): their members' types are the
  annotations and generic types the subset lacks, and describing them (some 120 methods of no
  parameter, a fifth of those described) cost mustache 16 KB. Refused: the library's own classes
  under a JDK's package that no JDK has public (`ImmutableCollections.ListN`); a library's class:
  m03 (below).
- **What runs (measured, decided): a member runs where the compiled program holds what it runs**
  (`inProgram`; an instance method where every body a call of it can run is lowered), and
  `Object`'s `equals`, `hashCode`, `toString` and `Enum`'s `name`, `ordinal`, `compareTo` wherever
  found (`ALWAYS_HELD`, k89's). Rejected: every member of every held class running -- the invokers
  reached the bodies of every method of no parameter of ~90 classes in mustache and, through
  `Class`'s own, would turn on the full tables of every class, a feedback no size bound holds; and
  an instance method held where one it overrides is called, which reached
  `ConcurrentHashMap.KeySetView`, whose `keySet` the program's narrowed dispatcher never ran. **A
  lookup naming a member by constants** (`String.class.getMethod("length")`) puts it in
  (`literalLookup`), as GraalVM's native image folds it. Any other use is refused where the
  program runs, the member still found with its types: `invoke of java.lang.String.isBlank() is
  not supported by the experimental Java subset where the program uses it only by reflection`; a
  member the library lacks `.. is not supported by the experimental Java subset yet`;
  `Object.getClass()` always (it would carry every class's class object, k89).
- **The representation (measured, m18)**: for the lookups by a name, ONE text of methods and one
  of fields (`%jdk-named-methods`, `%jdk-named-fields`), a line a class by its class object's place
  in `%jdk-types`, which `AccessibleObject.described` finds by identity -- no slot, no arm: an arm
  a class in `%class-method` and `%class-field` (an `eq` test of some 45 B of wasm, the call) cost
  the shiori 73 KB. A line: `+`/`-` public, the class's test number, a module other than
  `java.base`, `|`, entries `name flags returned index [exceptions]` (fields `name flags type`), the
  flags `d` default, `l` the library lacks it, `a` the program does not hold it, `uN` its use. The
  calls are three functions of a number (`jdkFunctions`): `%jdk-tests` a class's instance test,
  `%jdk-methods` a method's call, `%jdk-fields` a field's read and write; numbered by member, kept
  across a session's buffers (`Session.jdkNumbers`: a `Method` a variable holds calls by its
  number), arms of one form shared (`(or (eql k a) (eql k b))`). Against a function a class and a
  dispatcher of `eq` arms: the shiori -22 KB, mustache -3 KB. **Trap: on a threaded wasm target the
  one function went resumable whole** (+38 KB of `_async_resume`): `jdkSuspending` asks the
  analysis of each arm as a function of its own, then splits the arms that can suspend into
  `%jdk-methods-suspending` (wait, a JDBC execute, a lock: a few) from `%jdk-methods-plain`; the
  analysis runs twice (2.2 s of the shiori's). **Trap: dispatch with `cond`, not `case`**: a member
  that can suspend a thread may not stand under a `case` in a resumable body. **A `vector` call is
  a store an element on wasm (134 B against a list's 6, `.todo/m52`)**: `%jdk-types` and a class
  object's interfaces are a list made a vector (`classVector`; the shiori's 528 interfaces 86 KB,
  mustache 18 KB). Rejected (l64): a member as code (+124 KB in mustache); one vector literal of all
  classes; a function object a class. Constructors stay code (`Constructor.found`): few, made only
  for the full tables and the constructor of no parameter.
- **Protected** (both kinds of class), a JDK's answers: a protected member of another package is
  the caller's where the caller is under its class and, an instance member, the object under the
  caller; a constructor's never (its object is of its own class). A JDK class's member says `(in
  module java.base)` in the refusal; `setAccessible(true)` of a JDK member or class that is not
  public is `InaccessibleObjectException` in a JDK's words, the unnamed module's number the
  declaring class's identity hash in hexadecimal digits written by hand (`Integer.toHexString`
  brought the printer of numbers in a base, 10 KB); `trySetAccessible()` answers `false`.
  **Differs**: a protected static member's `setAccessible` is allowed whoever asks (a JDK checks
  the caller; the library's `setAccessible` is not told it).
- **Also**: a program class's `getMethods` gathers a JDK class above it as the table lists it, the
  library's own method standing for it (`jdkStandIn`), so a generic interface's method the program
  implements hides it; a reflective `Object.clone()` refuses a class not `Cloneable`.
- **Measured** (2026-11-25, wasm Preview 1): the shop, both YAVI examples, httpbin, mvcfn
  byte-identical; the guestbook +1,278 B (the held JDK classes' method names, the caller's classes
  and test at each call); mustache +10.0% (624,377 B), the shiori +7.4% (4,106,360 B): jmustache's
  lookups by name make every held JDK class -- of every value `getClass()` can answer and above,
  some 90 in mustache, 250 in the shiori -- describe its members. Of the shiori's +226 KB of
  functions, the class objects of those members' types 46 KB, the arms of `%class-method` and
  `%class-field` 36 KB, the classes' functions and two dispatchers 54 KB, the bodies those reach
  23 KB, resumable bodies 9 KB; and 52 KB of texts in data. **After m18** (2026-11-25, against
  4,117,376 and 624,348 the same day): the shiori 3,867,657 B, mustache 583,786 (pre-l64 3,823,485
  and 567,518). Not made: a class object only a member's type gives as data the library makes at
  first use -- its bound (no such object at all) mustache -4.6 KB, the shiori -32 KB, but there
  every class object carries an instance test (the program reaches `isInstance`), a lambda no data
  makes, and every function over `made` (`forName`, `getModifiers`, ..) would have to learn it.
- **jmustache**: a template naming a member the program calls nowhere (`{{blank}}`, `{{first}}`)
  fails with `MustacheException` where a JDK renders it (deviations.md).
- **Not here**: `canAccess`; the classes above a JDK class are the subset's (`getSuperclass()` of
  `StringBuilder` is `Object`), though `getMethods` gathers by the JDK's; a JDK's private and
  package members (none usable: `setAccessible` of one is refused under a JDK) and its order of
  `getDeclaredMethods` (ours by name).
- Pins: java-spec `a-class-is-a-value` (`jdk()`: the answers above against a JDK's on four
  backends, a template engine's walk to `Enum.name`, the protected members of `AbstractList` and
  `Object.clone` from under and outside); `JavaLoweringTest` (`L14`: a member called is in the
  class's function, one called nowhere `a`, one the library lacks `l`, a member's type and `Class`
  answer none); `JavaJdkMembersTest` (the table and the algorithms against the JDK).

## java.lang.reflect.Array and java.lang.invoke (2026-11-25, l65)

**Invariant: an array made of a class object is an array of that class, its class recorded where
it is made and its class object the one a literal or `arrayType()` answers; a method handle is a
member the class objects answer (k89, l64), found and checked by a lookup as a JDK's lookup does
and called through the type a compiler gives the call. A program naming neither package is
byte-identical.** Written from the API documentation (the members of `Array`, `MethodHandles`,
`Lookup`, `MethodType`, `MethodHandle`; `asType`'s conversions and `asVarargsCollector`'s rules;
JLS 15.12.3 for the call's type) and JDK 25's answers to probes.
Nobody opened OpenJDK's source ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **`Array`**: an array's kind is the letter its recorded class's name spells after the `[`
  (`%java-array-kind`), telling a `long[]` from an `Object[]` and a `short[]` from a `char[]`; a
  program calling a native of `Array` arms the records. Elements go through natives typed by their
  Java signatures, so Java's own boxing and widening answer. The refusals are JDK 25's, in its
  order (the dimensions' count before the lengths; for `get`/`set` null, no array, a primitive
  array's `null`, the index, the type) and words (`Argument is not an array`, `argument type
  mismatch`, `Argument is not an array of primitive type`; an `ArrayIndexOutOfBoundsException`
  with no message). **A reference stored into an array of references is not checked**, as the
  language's store is not (deviations.md): it would need every value's class tested against a
  class object by name.
- **One class object per array type** (`%array-type`, made by `Array.newInstance` only where the
  program asks an array its class object): `Array.newInstance(String.class, 0).getClass() ==
  String[].class`, as under a JDK.
- **Method handles are Java over reflection** (the library's `java.lang.invoke`), with k89's
  access, a protected member of another package the lookup class's (the handle then takes its
  objects); a call asks no access again and throws what the member throws as it is. A JDK
  lookup's words: `no such method: C.m(T)R/invokeStatic` (`NoSuchMethodException`,
  `IllegalAccessException`), `symbolic reference class is not accessible: class C, from ..`,
  `Cannot invoke C.m()R/kind with null receiver`.
- **`invokeExact` and `invoke`** (`JavaLowering.polymorphic`): the call's type as JLS 15.12.3
  gives it -- **a lambda's body of no value returns `Object`, javac's answer**. Made at the call's
  first run; in a session at each call (its buffers would share the global's name). Words:
  `handle's method type .. but found ..`; javac's `cannot be applied to given types` (a lambda
  argument), `'void' type not allowed here`.
- **`asType`'s conversions as the API documentation and a JDK's answers give them**, in a JDK's
  words (`Cannot cast X to Y`, `Cannot cast X to <the box of the type>`, `WrongMethodTypeException`
  `cannot convert MethodHandle(..)R to (..)R`) but a value that is no box, refused in the subset's
  cast words without a JDK's modules (`class X cannot be cast to class java.lang.Number`).
- **No box's class object in the conversions** (a box is asked of a value of it,
  `to.isInstance(zero(from))`): written as `Integer.class` and the seven others, they would be held
  class objects whose members l64 describes, `Adapted.java` 402,967 B of wasm against 265,581 B.
- **`MethodType`**: every factory makes a new one, `equals` but not `==` (a JDK's are, by a cache
  the specification does not promise). `void.class` is `Void.TYPE`, as javac writes it.
- **Measured** (2026-11-25): the examples and `Plain` (an `Object` array asked its class)
  byte-identical. Not made: folding a handle of constants into a call where the program is
  compiled would save at most the 31 KB of reflection in `Handled` (208,064 B of wasm), and only
  for lookups of constants.
- JUnit Jupiter's `assertArrayEquals` of `Object[]` runs, its messages JUnit 6.0.3's under a JDK
  but for identity hashes.
- **Not here**, refused by name: `VarHandle`, `MethodHandles`' combinators, `findGetter` and a
  field's other handles, `findSpecial`, `bind`, `Lookup.in`, `privateLookupIn`,
  `MethodHandleInfo`, `CallSite`, `LambdaMetafactory`.
- Pins: java-spec `a-class-is-a-value` (`arrays()`: each kind and its class object, arrays of
  arrays, every refusal; `handles()`: lookups, method types, exact and adapted calls with each
  refusal, a varargs collector, a JDK's member by constants, a statement's and a lambda body's
  type).

## Reflection from reachability metadata (2026-11-25, m03)

**Invariant: a class a computed name finds, and what of a library's classes reflection runs, is
what GraalVM reachability metadata declares, read where the program is compiled from
`META-INF/native-image/` of the source tree and of each root (a library's jar or sources jar
brings its own); a declared library class answers its members as a program's class does and runs
the declared ones; a program reflecting over no class an entry names is byte-identical.** The
user's choice (2026-11-24) over k89's tables for a library's class. Written from GraalVM's
published documentation of the format (its Reachability Metadata page) and the answers of JDK
25.0.5.1 and Oracle GraalVM `native-image` 25.0.5.1 to probes.
Nobody read GraalVM's or OpenJDK's sources
([java-frontend-provenance.md](java-frontend-provenance.md)). User-facing: `doc/*/java/index.md`,
"Reflection from reachability metadata".

- **What `native-image` answers (measured, default options); where this differs, the JDK's
  answer**: a type a `reachability-metadata.json` entry names answers every query, and an
  unregistered member run is `MissingReflectionRegistrationError: Cannot reflectively invoke
  method '<its text>'` (here the constructor of no parameter runs); an older `reflect-config.json`
  entry answers only the members it registers (here its class lists all); a type no entry names
  answers `[]` to `getDeclaredMethods` (here names, l37) and `NoSuchMethodException` to a computed
  `getMethod`, while a constant `getMethod("run")` or `getDeclaredConstructor()` on its class
  literal is folded and runs. `Class.forName` of a name no entry registers is
  `ClassNotFoundException` (`--exact-reachability-metadata`: `MissingReflectionRegistrationError:
  Cannot reflectively access the class 'X'`), of a constant folded, registering nothing for a
  computed name (here, `literalForName`, that finds it too). `typeReached` is asked as the program
  runs, no entry counting before the class is initialized (here a condition is the compile's
  reach, `reachedTypes`: an entry counts from the start of a program reaching its class anywhere).
  A JDK class is no different (`String.strip` no entry names is refused). On the probes every line
  this prints that differs from the JDK's, `native-image` prints differently from the JDK too.
- **Read only where the program reaches a member of `Class` that reflects** (`REFLECTED`;
  `JavaReachabilityMetadata`), by a JSON reader of its own (RFC 8259; no JSON is a compile error at
  `file:line:column`). Not read: proxies, lambdas, `unsafeAllocated`, `serializable`, JNI,
  `allRecordComponents`.
- **`Class.forName`** of a name no entry declares is `ClassNotFoundException`, GraalVM's default
  answer, which a library probing for an optional class (SLF4J, Commons Logging) catches.
- **A library's class an entry names** is described as k89 does a program's; `runs` decides what
  runs: a member an entry declares (by the types reflection answers it takes -- an inner class's
  constructor the instance around it first -- or an `all*` flag) and the constructor of no
  parameter, declared or not. Any other has l64's `MEMBER_ABSENT`, refused where used in l64's
  words. Rejected: GraalVM's `MissingReflectionRegistrationError`, an `Error` of GraalVM's own
  package a program cannot name under a JDK, whose new words would change every program reaching
  the refusal (mustache's `%class-method` arm). A lookup naming a library's member by constants
  declares it (`libraryLookup`). A library's class no entry names is refused (`.. whose members
  the program does not keep ..`).
- **An entry naming a JDK class** makes it findable and held (l64 describes it) and puts in the
  members it declares as a constant lookup does (`jdkMetadataMembers`).
- **The shared repository** (`oracle/graalvm-reachability-metadata`, CC0-1.0, release 1.1.14):
  2,445 metadata files, 49,003 reflection entries naming a class, 14,820 of them a JDK class and
  4,688 a class the library has (277 classes, 1,817 members) -- mostly what a JDK's own code asks
  by reflection where the metadata was traced (`Object.<init>()` 77,
  `Thread.getContextClassLoader()` 63, `String.serialVersionUID` 58, `Buffer.address` 49,
  `ClassLoader.classLoaderValueMap` 29). pgjdbc 42.7.3's 95: 20 of `org.postgresql`, each under
  a `typeReached`; 75 of the JDK, 62 its security providers (`sun.security.*`,
  `com.sun.crypto.provider.*`) and 13 of classes the library has (the `java.sql` methods a pooled
  connection's proxy calls, `AccessController.doPrivileged`). Commons Logging 1.2's 7 are all its
  own (`LogFactoryImpl.<init>()`, `Jdk14Logger.<init>(String)`: what Commons Logging without
  SLF4J needs); `jakarta.servlet-api` has none of reflection, `slf4j-api` two of the JDK.
- **Decided: the repository is read by a switch**, `--java-metadata-repository VERSION|PATH`, for
  the coordinates `--java-source-dep` resolved (the Maven plugin's `metadataRepository`). Not by
  default: a network fetch (3.4 MB) of an artifact the program does not name; GraalVM's plugins
  read it by default because `native-image` refuses whatever nothing declares, where the subset
  answers a program's classes whole and a JDK class's members where it holds them. **Its entries
  naming a JDK class are not read** (`ofTheJdk`): they describe a JDK's own reflection, which the
  library, written apart, does not make. Measured with Commons Logging 1.3.6 (a program logging
  once, the interpreter): its own two entries make it pick `Jdk14Logger` and log as a JDK does;
  the repository's seven also declare `SimpleLog.<init>(String)`, whose members name
  `java.text.SimpleDateFormat`: the compile was refused, and since m17 (2026-11-25) it warns
  seven times and runs (`describedAbsent`, "A library from the source path"), so the reason
  left for not reading by default is the fetch.
- **Measured** (2026-11-25, wasm Preview 1): the examples (the shop, both YAVI examples,
  mustache, httpbin, the guestbook, mvcfn, the shiori) and `examples/java/jdbc`'s `Notes` (its
  sources hold no metadata) byte-identical. `examples/java/reflection` 828,680 B, of which YAVI's
  one entry (`ViolationDetail`, all its declared methods; without it the program refuses there)
  3,695 B and the program's own two entries some 16 KB. pgjdbc's 20 entries are not measured:
  pgjdbc does not compile yet (`.todo/l22`).
- **Pinned by** java-spec `a-program-uses-a-library-read-as-source-from-its-source-path`
  (`reflection()`: the cases above, an older entry in the library's jar and a condition reached
  among them, the JDK's answers on four backends);
  `JavaClassPathCliTest#aJavaProgramCompilesItsLibrariesFromTheSourcePath` (a member no entry
  declares refused, a class none names not found, the repository's entries read and its JDK ones
  not, metadata that is no JSON refused at its position); `examples.yaml`'s
  `java/reflection/src/main/java/com/example/reflection/Reflection.java`, which `native-image`
  builds from the same metadata (`mvn -Pgraalvm package`), printing the JDK's lines there too.
- **Not here**: `Class.forName` of an array type (`[Ljava.lang.String;`); a class above a declared
  library class is not declared by it (`getMethods()` under a library's class no entry names is
  refused: `under ..`); record components ("Generic types, records and the annotations of
  members", below); a configuration file of another name a library's `native-image.properties`
  names (`-H:ReflectionConfigurationResources`).

## Generic types, records and the annotations of members (2026-11-25, l66)

**Invariant: a declaration answers the generic types it writes, a record its components, and
every declaration the annotations written on it, as a JDK 25's reflection answers them -- of a
class of the program and of a library's class reachability metadata declares from what the
lowering knows, of a class of the JDK from the table of its members; each through a function the
lowering makes only where the program reaches the member asking it, so a program asking none is
byte-identical.** Written from the API documentation (`Type` and its four kinds, `Parameter`,
`RecordComponent`, `AnnotatedElement`, `Package`; `Annotation`'s `equals` and `hashCode`; JVMS
4.7.9.1 for a signature's text; JLS 9.6.4.1 for `@Target`, 9.7.5 for a container) and JDK 25's
answers to probes.
Nobody opened OpenJDK's source ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **Generic types** (`%class-generic`): `NIL` where a declaration names none, answered with the
  erased types, so a class naming none costs one arm of an `or`; a type variable's bounds are
  asked lazily, as one may name the variable (`S extends Comparable<S>`); texts, equality and
  hashes as the probes show a JDK's (`Generics.java`, `Edges.java`). **A constructor's generic
  parameters are its signature's**: javac keeps one for an enum's always (`Size(String)` answers
  `[String]`, `getParameterTypes()` `[String, int, String]`) and for any other declaring type
  parameters or naming a generic type; with none they are the erased ones, an inner class's
  instance around it included.
- **The JDK's classes** (`jdk-members.txt`, with JVMS signatures and the annotations as the JDK
  writes them: 238 classes with a signature, 2,312 members, 538 annotations, 7 of parameters;
  815 KB, 612 without): a listed class's generic types answer for each of its class objects, its
  members' where it answers its members; `Class` and `java.lang.reflect`'s none, as l64 decided
  for their members.
- **A record's components** (`%class-components`): a JDK's, a new `RecordComponent` each.
- **Parameters** (`%class-parameters`): what javac with no `-parameters` keeps (a record's
  canonical constructor's names, the modifiers of what a compiler adds), read off its class
  files through a JDK's reflection; one a compiler adds has its erased type for
  `getParameterizedType()`, as a signature leaves it out.
- **Annotations of members and the rest** (`%class-annotations`): of a type of runtime retention
  whose `@Target` names that kind of declaration or that has none (`JavaClasses.appliesTo`;
  JSpecify's `TYPE_USE` `@Nullable` on a method is a type's, not the method's), a record
  component's where javac puts them by their targets. Held beside the class's holder, so a
  program reading a class's alone holds what it held. `getAnnotations()`: the inherited, then
  the own, one of the same type taking the inherited one's place (a JDK's `LinkedHashMap`,
  `Texts.java`). **Repeated annotations are one of the container**, as javac writes the class
  file: `getAnnotation(Role.class)` of a class carrying two answers `null`, as a JDK's.
- **A package**: a `package-info.java` of the program's source tree is parsed where its
  annotations are asked, as a JDK's source launcher compiles one on demand; not a library
  root's.
- **The JDK's annotations** (`rontolisp.internal.JdkAnnotations`, the table's text): an instance
  where the library has the type, else one answering its type alone (`jdk.internal.ValueBased`
  ..). `java.lang`'s `Deprecated`, `FunctionalInterface` and `SafeVarargs` are library
  annotation types (`@Override`, `@SuppressWarnings` none: source retention), so a program's are
  carried as a JDK carries them, and the library's annotation types declare the JDK's
  meta-annotations, which a program's annotation type then carries.
- **`equals`, `hashCode` and `toString` of an annotation**: `Annotation`'s hash code, a JDK's
  text (`Texts.java`, `Escapes.java`). **The order is a hash map's**: the elements with a
  default as a `HashMap` grown from no capacity iterates their names, then the others as
  written; two defaults of one bucket in the order declared, where a JDK uses its VM's order of
  the type's methods, which the probes show differs (`Quoted`'s `c` and `min`): `deviations.md`;
  no test names two of one bucket. **Made in full only where a program's own code reads an
  annotation or anything lists them** (`JavaClasses.annotationsRead`): else the guestbook, whose
  container reads its servlets' annotations, holds them through the dispatchers of `equals`,
  `hashCode` and `toString` on any object, +20 KB measured.
- **What it took to keep the others byte-identical, measured**: `Package` keeps no `HashMap`
  (one declared with `Class` moved `AbstractMap`'s structure ahead in every program); the class
  objects of `Class` and `java.lang.reflect`'s list `Type`, `GenericDeclaration` and
  `AnnotatedElement` only where the program asks a generic type or an annotation list, holds
  such an interface's class object or tests an array of one (jmustache's walk of
  `getInterfaces()` over the reflection classes was +1,345 B).
- **Measured** (2026-11-25; wasm Preview 1 / component / JVM class, B): the shop, both YAVI
  examples, mustache, httpbin, the guestbook, mvcfn, jdbc and `examples/java/reflection`
  byte-identical; the shiori 4,109,210 / 4,286,856 / 4,196,951 -> 4,109,332 / 4,286,878 /
  4,196,714 (jmustache's walk describes a held `ClassLoader`, whose `getDefinedPackages()` names
  `Package[]`). Reading an annotation's elements (`Read.java`) byte-identical; printing one and
  comparing two (`Printed.java`) 87,501 -> 91,016; a super type token (`Token.java`) 133,025;
  the test's part (`Part.java`) 1,250,126.
- **Not here**: a type variable of the method around a local class (its declaration `null`);
  identity -- a JDK answers one annotation object and one type variable each time, this a new
  one; the members of `Class` and `java.lang.reflect`'s by reflection; names under
  `-parameters`. Annotated types and a type variable's annotations: next section.
- Pins: java-spec `a-class-is-a-value` (`generics()`, `annotated()`: each kind of type, element
  and declaration), `a-program-of-several-files-finds-its-classes-by-package` (a
  `package-info.java`); `JavaJdkMembersTest` (the table).

## Annotated types and `@Target` (2026-11-26, m30)

**Invariant: a use of a type a declaration names answers, as a JDK 25's reflection does, the
type annotations of runtime retention written in it, and an annotation stands only where its
type's `@Target` lets it, refused with javac's words.** Written from the API documentation
(`AnnotatedType` and its four kinds, `TypeVariable`, `ElementType`), JLS 9.6.4.1 and 9.7.4 and
JDK 25's (javac's) answers to probes; nobody opened OpenJDK's source.

- **What the parser keeps** (`JavaSyntax.TypeAnnotations`, by a type's position): the
  annotations ahead of each word of a type's name (the first's ahead of the type) and of each
  `[]`, a variable arity's `...` the innermost dimension (`String @A [] @B ...` is
  `String @A [] @B []`, measured); and where each annotation on a declaration stands
  (`JavaSyntax.AnnotationSite`). Annotations between a method's type parameters and its type
  are its modifiers' (JLS 8.4); a receiver's are its type's.
- **Where a declaration's own stand** (`JavaClasses.placed`): of a `TYPE_USE` type, on the
  first word of the type a type annotation may stand ahead of (`admissible`: the last word, or
  a class around it, every class stepped through an inner one), an array's element. Where the
  word written is a package or a class scoping a static member, javac -- for a type whose
  `@Target` names the declaration too -- writes it on the type the name names (`@Both
  java.lang.String`, `@Both W.N`: measured), not nowhere as JLS 9.7.4 reads.
- **The runtime** (`rontolisp.internal.AnnotatedTypes`): a use is made of the generic type and
  the paths the lowering answers (`%class-annotated`, `annotated0`: `C` component, `O` owner,
  `A<n>;`, `U<n>;`, `L<n>;`). Owner of a class: the class it is a member of (`annotated0` 7),
  none of a local or an anonymous one. Receiver: `Types.self` (a parameterized type where the
  class or an inner class's outer has type variables); a constructor's of an inner member class
  alone; a constructor's return type the plain class chain. Texts, equality (type, annotations,
  owner, parts; a new object each time) as probed; hash codes consistent with equality, not a
  JDK's numbers (a JDK's start from `Class.hashCode`, an identity hash).
- **Parameters, measured**: over the generic types where there is one a parameter, else the
  erased ones; an inner member class's constructor's annotations after the instance around it;
  an enum's and a local class's on the parameter in the declared one's place, shifted by the
  name and ordinal or the instance, as a JDK's are.
- **Which declaration carries a `TYPE_USE` one** (`JavaClasses.appliesTo`, measured): a class,
  a type parameter and an enum constant (whose field's type then carries none); a field, a
  method, a constructor, a parameter or a record component not (`declaresOn` is the check's
  wider rule: a record component takes what its field, accessor or parameter does).
- **A type variable's annotations** (`%class-variable-annotations`, `variableAnnotations0`):
  a function of its own -- reached through `AnnotatedElement` by any program holding type
  variables, it would otherwise make `%class-annotations` take a computed `what` (+7.4 KB of
  wasm measured on `Classes.java`); arms only for annotated type parameters and refusals for
  reflected library classes.
- **The check** (`JavaClasses.applicable`, a program's files alone): `annotation interface not
  applicable to this kind of declaration`, `annotation @X not applicable in this type context`
  (a type parameter's too), `type annotation @X is not expected here` (canonical name), as
  javac 25 writes them; `java.lang`'s targets from their documentation, no file read.
- **Measured** (2026-11-26; wasm P1 / component / JVM class, B): shop, both YAVI examples'
  `Member`, mustache and reflection byte-identical; `a-class-is-a-value`'s program before this
  item's lines 3,784,952 / 3,786,316 / 2,418,617 -> 3,785,352 / 3,786,716 / 2,419,100 (its type
  variables are `AnnotatedElement`s, as a JDK's: their class's interface list and the
  dispatchers of `AnnotatedElement`); a program printing one annotated field type 172,552 /
  174,614 / 236,581, against 135,988 (wasm) printing its generic type.
- **Not here**: a member type of a generic class named through the raw class (`Outer.Inner` in
  `Outer<T>`): a JDK's raw class, the subset's parameterized type, as its generic type already
  was; the annotations of a library class's types (refused, as its annotations are); a JDK
  class's (none written, `NIL`).
- Pins: java-spec `a-class-is-a-value` (`typed()`), its two `refused:` `@Target` cases;
  `JavaLoweringTest#anErrorTheLoweringDependsOnIsReportedAtItsPosition` (the scoping refusal,
  which needs a `TYPE_USE` type no JDK class is).

## A session (2026-11-17, j74)

**Every buffer lowers the whole session again**: the earlier buffers' declarations, kept as
TEXT, follow the buffer and every function is defined again, so a later buffer's subclass,
overload or new body reaches an earlier caller by name. Rejected: a call table a later buffer
replaces (every call of a closed program indirect, two lowerings to keep); lowering the affected
declarations again (what a change reaches is the program). Measured (2026-11-17, interpreter): a
buffer after 120 declarations 10 ms, after 480 28 ms. A failing buffer leaves the session as it
was; `$<n>` numbers expressions as a snippet shell does (`doc/*/java/index.md`).
- **What does not run again**: every class is lazy (`JavaInitOrder.allLazy`), set up by the
  buffer declaring it or once where none does, so a static field lives on; a variable whose type
  names a class declared again is set to `null` (its instance has the old slots). Carried for
  earlier values: the library classes made (a dispatch must reach an `ArrayList` no later buffer
  names), a lambda's interfaces, every one tagged (no later buffer may find an untagged one it
  must tell apart), whether `Thread` was reached. An enum's `$VALUES` is added where the enum is
  declared, as a later buffer's `values()` finds it initialized and nothing would set the field
  (`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot`).
- **No source tree**, as `-e`: the REPL's working directory is no package root, and a snippet
  shell reads none either; the source path is read
  (`JavaClassPathCliTest#aJavaProgramCompilesItsLibrariesFromTheSourcePath`).

## The playground's uploads (2026-11-20, k44)

**The uploaded files are the source tree of the page's program, which is read from no file**: a
class it names is read from the upload its qualified name spells, as a source launcher reads a
file beside the one it starts at, and checked as the program's own. Not a root: a root's file is
a library's, trusted and lowered as reached ("A library from the source path") because its
authors compiled it; nobody compiled an upload, and `javac -sourcepath` checks a file it reads
there as one it was given. The uploads are a directory (`eval/UploadedFiles`), so a computed
name finds one and the page's other languages see directories, where a host with none answered
nothing a user chose (a WIT package directory binds; a loader listing none refuses it:
`a WIT package directory is read only where the loader lists directories`). Pinned by
`PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram` (an upload's
error at its own position), `WitImportInlinerTest` (the page's loader) and
`web/playground-check.js` (the image's `.wasm` button, a computed name).

## Across languages (2026-11-17, j74)

**Nothing is converted but a `char` and a `boolean`; the door is a call, both ways**, as
[starlark-frontend.md](starlark-frontend.md)'s. `(load "Geometry.java")` runs a file's `main`,
or with none defines its classes (the CLI entry refuses no `main` but where it is a reactor:
"WIT exports", "A reactor of public static methods"); a static method is
`|Class.name(erased,params)|`. A program's `native` static method is the Lisp function its name
spells (`JavaForms.lispName`: `toJSON` -> `TO-JSON`), a `char` `code-char`ed and `char-code`d, a
`boolean` result `(if r t nil)`, a `String` result `%java-lisp-string`; an instance one is
refused. A Lisp list is no `List` (its dispatch has no arm for a cons): a vector crosses. Pinned
by java-spec `common-lisp-and-java-call-each-other` (a `.lisp` standalone, no JDK leg; four
backends).

## Annotations (2026-11-17, j74)

Parsed and dropped, on a declaration and on a type, but for what a program reads where it runs
(next section; "Generic types, records and the annotations of members" above). The name must be
an annotation type the file sees (`java.lang`'s, no file read; `java.lang.annotation`'s; an
`@interface`, a `java.lang.annotation.Annotation` by JLS 9.6): `cannot find symbol: class X`,
`incompatible types: X cannot be converted to Annotation` (javac's); where its `@Target` does
not let it stand, javac's refusal ("Annotated types and `@Target`" above). An unknown name is refused
because a processor or a framework gives it a meaning whose absence would change the program.
Decided for a dependency by what javac does with a class file: an annotation whose type is
missing from the class path is dropped silently, so a library's file is not asked
(`Source.partial`). `@Override` and `@FunctionalInterface` are checked;
`@SuppressWarnings("unchecked")`: "Warnings" below.

## Annotations read where the program runs (2026-11-22, l21)

**Invariant: an annotation a program can read is one of a class of the program or of its
libraries, of a type whose retention is RUNTIME, read through the class's object; what makes it
is Java source the front end writes and lowers as far as the program reaches it; and a program
that reads no annotation holds none of it.** What `servlet-httpserver`'s discovery asks with the
code it runs under a JDK (`.kb/servlet-httpserver.md`, "Discovery"; the user's decision of
2026-11-22: `Class.getAnnotation` for RUNTIME annotations and the list of annotated classes
prepared when the program is compiled). Members: "Reflection in full", "Generic types, records
and the annotations of members" above.

- **The class literals a program names decide what it can read, with no flag**:
  `%class-annotation` is a `cond` over the pairs (class object, annotation type's class object)
  BOTH of which the program has -- a type whose class object is not made can equal nothing
  asked. Never the front end's own library's: its source is not the JDK's.
- **An annotation is an instance of a class written as Java** (`JavaAnnotations`, a member class
  each, declared `DEPENDENCY`: only the elements asked are lowered). Rejected: one class a TYPE,
  an element a field and a constructor parameter (`@WebFilter`'s eleven elements made a
  constructor of 1,935 B of wasm, every default computed for every annotation; the guestbook's
  holders: 1.3 KB). **Each call answers a new object and each element a new array**, as a JDK
  clones (a JDK answers one object for an empty array, which the corpus does not ask).
- **A class by a computed name: the classes an index lists** (`%class-named`; else
  `ClassNotFoundException`, but see "Reflection from reachability metadata"): a compiled program
  has no class file to look in, so it finds by name what it was told to look for -- the index
  `META-INF/annotations/<annotation type's binary name>`, asked by a CONSTANT name and generated
  for the tree and each root. **A file the program never names joins the program**: the tree is
  a class path root, as a JDK's scan reads every class file of one, so two programs of one
  directory share their annotated classes (measured). Rejected: listing every class of the
  program (a class object, its constructor and what it reaches for each); answering a computed
  name (which type is asked is then unknown, and the lookup carries every file).
- **`getDeclaredConstructor()`** (`%class-constructor`): **`newInstance` is a native told the
  class calling** (the unit's package and top-level class as constants), a caller-sensitive
  check with no stack, as a JDK answers (corpus). **Static initialization**: these functions are
  lowered with their effects dropped; the call sites carry them.
- **Three questions a library asks are answered while the program is compiled**
  (`JavaLowering.answered`, in a `partial` source only: a program's own file keeps its flow
  analysis), so what the answer does not take is not lowered: `System.getProperty` of a key no
  target has `== null`; `isAnnotationPresent(T.class)` where no class carries `T`; a
  `ClassLoader`'s `getResource` / `getResourceAsStream` of a constant name no place holds
  `== null`. An `else if` is asked link by link, else a class made only under an answered one is
  lowered. Measured: 53 KB off the guestbook's component (a library's `java.class.path` scan),
  54 KB off a one-servlet hello world (the two registration kinds it has no class of); the third
  keeps `servlet-httpserver`'s default servlet out of a program with no `META-INF/resources`
  (22 KB of the guestbook: [servlet-httpserver.md](servlet-httpserver.md), "Static resources").
- **The library**: `FileSystems.newFileSystem(Path)` finds no provider
  (`ProviderNotFoundException`), as a JDK without the zip file system.
- **Measured** (wasm P1, 2026-11-22): reading one annotation 87,676 B, ~25 KB of it what a
  program that throws carries; `examples/java/shop` and a program holding an annotated class's
  object byte-identical with and without; the corpus probe prints a JDK's lines on four
  backends. The guestbook: `.kb/servlet-httpserver.md`, "The example".
- **Pinned by** java-spec `a-program-uses-a-library-read-as-source-from-its-source-path`
  (`annotations()`: an index file the JDK leg needs and the others find beside the generated
  one; `geo.Marked.asked`: untaken branches naming the refused `Thread.getAllStackTraces()`) and
  `FetchSpecE2eTest#javaHttpServer`'s `guestbook` (no index file).

## WIT imports (2026-11-22, l26)

**Invariant: a Java interface annotated `@WitImport` stands for a WIT interface: its abstract
methods are the WIT functions the program calls, checked against the WIT on every target; its
implementation is a `ServiceLoader` provider -- the program's own under a JDK, on the
interpreter and on the JVM, the host's on a WASM target, where it is a BINDING of generated Java
source over a `rontolisp:wit-import` naming the members the program reached.** The user's
decision (2026-11-22): WIT imported from Java through an annotation, in a library of its own
that `javac` and a JDK build and run. User-facing: `doc/*/java/wit.md`.

- **The library** `rontolisp-java-api/` (no dependency, outside the root reactor) is read from
  the source path, its annotation known to the front end by qualified name alone; it holds what
  only rontolisp gives a program, as plain Java that behaves the same under `javac`.
- **An interface, not `native` methods**: a JDK runs no `native` method a program supplies (JNI
  only); a provider implements an interface on the JDK, the interpreter and the JVM alike.
- **The implementation is the JDK's own SPI**: `META-INF/services/<binary name>` of the source
  tree (measured: JDK 25's source launcher finds one there, unnamed package included), so a
  program runs on the interpreter and the JVM as on the JDK, identity included, nothing
  converted; on WASM a listed provider is not consulted (`wit-provide`'s rule). Rejected:
  `wit-provide` on the interpreter and the JVM as Clojure does (every value converted twice, a
  provider's objects copied where the JDK hands them over, an interface with no provider present
  where the JDK has none); a static lookup in the library (its body would be the JDK's, and the
  front end would have to recognise it).
- **The check** (`JavaWitImports.Checker`) covers the members declared alone, so an interface
  declaring what two drafts of `wasi:keyvalue` share checks against either (`list-keys`' cursor
  is `option<u64>` in draft, `option<string>` in draft2). Of that page's refusals, an `option`
  of `bool`, `list`, flags or `option` is refused because the boundary's none is nil, as is each
  of their values, a tuple in an error arm because no type is written for it.
- **The binding** (`JavaWitImports.Writer`): `%raw` for a result's envelope, so wit.lisp is not
  spliced for the arm; `:octets t`, a `byte[]` being already the vector a component lifts
  (measured: no conversion).
- **Where the WIT is: a resource of the interface's class** (l75, so a library's WIT can live in
  `src/main/resources`), of a directory or a jar of the source path (a sources jar of
  `--java-source-dep`). Decided: `.` and `..` are resolved in EVERY place, a jar's included,
  unlike a run-time resource there -- the compiler reads the WIT and no loader ever does, so
  there is no JDK answer to match; a climb above a root's top is refused
  (`it names a resource above the top of a root`); not found:
  `no file of the source tree and no root of the source path holds kv/wit/x.wit`. The directive
  names a jar by its absolute path, as it lies in a repository, not beside the program; a
  sources jar merges `src/main/java` and `src/main/resources`, so a WIT in either is the same
  entry. Clojure keeps the file-relative rule (`.kb/clojure-frontend.md`).
- **Measured** (2026-11-22, wasmtime 51.0.0): the boundaries stage a `u32` argument differently
  -- a component's import takes its value (`-1` traps "integer overflow"), a core module's the
  `s32` of its bits (`4294967295` traps "unreachable") --, so the binding masks a `u32` under
  `--component` (and `--no-wasi`, below), not on Preview 1; a `u64` past `Long.MAX_VALUE`
  crosses no wasm-GC boundary (`.todo/169`). `--native`, a Preview 1 module whose runner
  provides no WIT interface, refuses the counter at the WIT line, and a flat interface's program
  exits 1 at its start (`unknown import: calc::add has not been defined`), as a Lisp
  `wit-import`'s does. `examples/wit/keyvalue/PageHits.java` as a component 236,739 B, 249,819 B
  on `new MemoryStore()`: the binding costs less than a map of its own.
- **On a `--no-wasi` module** the directive carries `:canonical t`
  ([wasm-import.md](wasm-import.md), "A WIT interface beyond the flat set"), the binding written
  before the reached set is known. Such an import may suspend: a program reaching one from
  `main` is refused (`PageHits.main(String[]) -> Store$Wit.open(String)`), and every export is
  promising, as the call walk does not follow the servlet container's dispatch to the store.
- **A library's interface**: `servlet-httpserver-keyvalue/`
  ([servlet-httpserver.md](servlet-httpserver.md), "Sessions in `wasi:keyvalue`"); a host's own,
  no JDK provider: `jdbc-spin-postgres/` ([jdbc-spin-postgres.md](jdbc-spin-postgres.md)).
- **Pinned by** `JavaWitImportTest` (a JDK, the interpreter, the JVM, the component, the
  Preview 1 refusal; the WIT's lookup; every kind of value; the checks, the two drafts) and
  `examples.yaml`'s `wit/keyvalue/PageHits.java`.

## WIT exports (2026-11-24, l85)

**Invariant: a class annotated `@WitExport` stands for a WIT world: each function the world
exports is a static method of it, each interface it exports a static member class of it, checked
against the world on every target; a program whose entry class has no `main` starts at the
`@WitExport` classes of its source tree, and on a component target lowers to a
`rontolisp:wit-export` of each world with `:reactor t` -- no new export path, the world's
exports the component's entry points.** The user's request (2026-11-24): the opposite of
`@WitImport`, and the premise that a program starts at a `main` gone.

- **The shape, decided**: named as `@WitImport` names; static because a component's export is a
  function with no receiver -- the component instance's state is the program's static state, and
  under a JDK a test calls `Counter.vowelCount` as it is. Rejected: a class implementing a Java
  interface for an exported WIT interface (a Java interface the WIT already is, an instance
  nothing else names); a class per interface naming it in `value` (it loses the world, the
  export list a composition checks).
- **The check** (`JavaWitExports.Checker`; the world through `WitExportDirective.describe`, so a
  type the export boundary does not carry is refused at its WIT line in `wit-export`'s words)
  refuses an instance method (no host has an instance) and an `async func` export; two exports
  of one label are two methods, keyed apart in `:names` (`.kb/wit.md`). Only the program's own
  classes are checked and exported (`Source.partial`).
- **Where a program starts** (`JavaLowering.exportEntries`): an entry class with no `main`
  starts at the source tree's `@WitExport` classes, found as the index finds classes, so the
  Maven plugin's entry may be any file declaring one. Elsewhere than on a component it is
  refused ("no main method found in Counter: its entry points are the exports of the @WitExport
  class Counter ..."): the interpreter and the JVM have no host to call it, a module no WIT type
  for a world, and `--native` is a module. A program WITH a `main` is a command, its
  `@WitExport` classes exported nowhere: both would need the export path to run the setup
  without `main` (`_start` is both), else the first export call runs `main`. Decided against
  until a program needs it.
- **The lowering**: an unsigned integer crosses through a `defun` `<function>%wit` converting
  it; a `u64` past `Long.MAX_VALUE` traps (`.kb/wit.md`). An EAGER class is initialized in the
  top level, which the reactor runs in the first export call, a LAZY one at the top of its
  static methods, as no entry form initializes the class such a program starts at
  ("java.util.logging", "The host's log").
- **Uncaught** (`JavaLowering.reportingExports`): the top level is `(%reporting-uncaught
  #'line nil init..)`, so an export call, and the initialization its first call runs, write
  the line a command writes (`Exception in thread "main" ...`) and trap; `after` is nil, a
  host call ending as a trap. Not on a reactor (`JavaBoundary.reportsUncaught`): a
  `--component --no-wasi` component imports nothing, its standard error a sink (the counter
  7,202 -> 6,748 B without the report, 2026-11-26), and `--no-gc` has no condition to write a
  line from. It is what a command pays, on wasm-GC EH mode: measured
  2026-11-25, `examples/java/rust-calls-java`'s counter 7,853 -> 15,056 B, gzip 4,006 -> 6,961
  (EH mode and the `%warn` +4.1 KB, `%java-uncaught-line` and the host-report readers +3.1 KB), a counter
  that throws 3,898 -> 43,738 B, the same code as a command 49,226 B. The `--no-wasi`
  reactor of public static methods keeps its silent trap: its standard error is a sink.
- **Measured** (2026-11-25): `examples/java/rust-calls-java`'s counter 7,853 B before the report
  above (8,164 while it
  imported `wasi:cli/exit`, which no export calls: 291 B, not the 1 KB first estimated), the
  Lisp one of `examples/wit/rust-calls-lisp` 3,879 B over `(find c "aeiouAEIOU")`.
- **Pinned by** `JavaWitImportTest.aClassImplementsTheWorldAComponentExports` (each scalar kind,
  an interface, static state, each refusal, no `wasi:cli/exit`; JDK, interpreter, JVM,
  component, the `--no-gc` component and its bare trap) and
  `examples.yaml`'s `java/rust-calls-java`; by hand, the example's `CounterTest` under
  `rontolisp test`, `MavenBuildE2eTest` with no `<mainClass>`.

## A reactor of public static methods (2026-11-24, l94)

**Invariant: a program with no `main` and no `@WitExport` class, compiled to a core module with
no WASI (`--no-wasi`), exports each public static method its entry class declares as a
`rontolisp:wasm-export` named as the method -- no new export path, a Lisp reactor's glue and
host calls it; every other target refuses it.** The user's request (2026-11-24): a Java program
with no `main` usable from a browser page or a Cloudflare Worker, with nothing of `@WitExport`.

- **The shape, decided**: the public static methods the entry class DECLARES, named as written
  (`:as "vowelCount"`, the JavaScript property), not `wasm-export`'s lowercased symbol; on a JDK
  the class is an ordinary library a test calls. Rejected: an annotation marking the exports (a
  dependency on `rontolisp-java-api` for what `public static` already says); every static method
  (a helper would need a modifier it does not want).
- **Types** (`JavaReactorExports.boundary`): the Java values as they are, nothing converted;
  refused at the method: `float` (no f32 boundary), `char` (a CHARACTER no integer boundary
  carries unconverted), arrays and objects, a second method of one name ("a module exports a
  name once"). A `null` `String` result traps (`illegal cast`), an uncaught exception traps
  unreported (`unreachable`), as a Lisp reactor's condition does.
- **Where it is decided** (`JavaLowering.exportEntries`): a program WITH `@WitExport` classes
  keeps "WIT exports"' rules on every target (its `--no-wasi` refusal says `--component`). A
  component with neither stays refused: its exports are a WIT world, which `@WitExport`
  declares, and two ways to state them were not wanted. Its IR: `--dump-ir --no-wasi`
  (`ir-dump.md`).
- **Initialization**: `_initialize`, which `--emit-js-glue`'s `instantiate` calls, runs the
  eager classes' static initializers once, as a Lisp `--no-wasi` reactor's does: the core
  reactor's ABI, shared by the Lisp glue and the WASI reactor convention, not the component's
  init-once flag. A lazy entry class is initialized at the top of its static methods: a reactor
  has no entry form to do it, and a `static final Logger` would read `null` in every export
  ("java.util.logging", "The host's log").
- **`--no-gc`**: the scalar subset of the same reactor ("A reactor on `--no-gc`" below).
- **A quit is refused only where an export reaches it** (`uiop:quit needs a host exit call`,
  after `LibraryDefunPruner`): `java.lisp`, spliced whole for any `%java-` reference, holds
  `System.exit`'s and the threads' exits, so refusing a mere reference fails every Java reactor
  that can throw (`Integer.parseInt`).
- **Measured** (2026-11-24): `examples/cloudflare-workers/hello-java`'s `Worker` 3,956 B at the
  default `--optimize`, 5,000 B at `=size` (`.kb/wasm-int-fusion.md`); the same three functions
  in Lisp with `princ-to-string` 11,597 / 9,014 B (the printer; Java's `%decimal-piece` is not).
- **Pinned by** `JavaReactorTest` (JDK, interpreter and JVM against the module under node:
  types, static state, an exception path, refusals), `JavaLoweringTest`'s refusal row,
  `examples.yaml`'s `cloudflare-workers/hello-java/Worker.java`; by hand, `WorkerTest.java`
  under `rontolisp test` on the four targets.

## A reactor on `--no-gc` (2026-11-25, l97)

**Invariant: a reactor of public static methods compiles `--no-gc` to the scalar backend's plain
module wherever every value it holds is one that backend has -- a primitive, a `String`, a
`double[]`, a structure nothing reads -- and is refused by name, at the Java position, where it
leaves that subset. The lowering is the `--no-wasi` one, unchanged; what Java needed the backend
learnt as Lisp (`.kb/no-gc-scalar-wasm.md`: "The top level", "A structure nothing reads; an error
is a trap", "Exact mode"), so no backend learns a Java name.**
- **What the premise listed, each measured, then made**: `%mask-signed-field`, which every
  integral operation is (a `long`'s `/` and `>>>` two shapes of it); the top level (a class's
  `defstruct`, which nothing reads; the statics' `defvar`s, wasm globals; an eager class's
  initializers, the start function); `java.lisp`'s exception path (a `throw` makes a structure
  nothing reads and signals it -- a trap, as the `--no-wasi` reactor's uncaught exception is);
  the `+` of a `String` (`%string-concat`, `%decimal-piece`, a `char`'s `string`: made; a
  double's text: refused, below).
- **Sizes** (2026-11-25, default `--optimize`, raw / gzip -9): `examples/cloudflare-workers/
  hello-java`'s `Worker` 1,103 / 699 B against 3,956 / 2,089 on `--no-wasi` (`../hello`'s Lisp is
  465 B); `JavaReactorTest`'s `Tally` 3,770 / 1,956 against 11,102 / 5,297; a 44-method probe of
  the subset 13,552 against 28,328 (`Math.pow`/`sin` pull the fdlibm runtime into both).
- **Exact** (the module's `Builder.exact`, set for a lowered program): the run time's index or
  nil is nil apart from 0 (`.kb/no-gc-scalar-wasm.md`, "Exact mode", with the sizes), so
  `substring` (both), `compareTo`, `indexOf` and `lastIndexOf` of a `String` and of a `char`,
  `contains`, `Integer.parseInt`, `Long.parseLong`, `Character.digit` and a `double` in a `+`
  answer as a JDK; a test of a float is refused, an index outside a `double[]` traps, a null
  `String` result traps. A `float` computes (`%single-float`: demote and promote, as on wasm-GC);
  its text refuses (`rational` in `%java-float-string`).
- **`%java-unicode-digit`'s zeros are a string** (2026-11-26), read with `char`: a quoted list is
  conses, which `--no-gc` has none of ("`Character.digit`" below, with what it measured).
- **Outside the subset, refused where the backend meets it**: a read of an object (`reads a
  structure (Point)`), an array of another element type, a lambda, a box (its null test), a
  `StringBuilder`, `try` (`handler-case`), a lazy class (its `<clinit>` catches to wrap in an
  `ExceptionInInitializerError`), `toUpperCase`, a static `String` assigned during a call (the
  call's memory is given back when it ends). At the innermost form the lowering locates, or
  with the chain of methods that reached a library's function.
- **A command is refused** (`exports nothing`): a `main` makes one, and the scalar module is a
  reactor (`.todo/111`).
- **`--component`: a `@WitExport` world** ("WIT exports"), the lowering the GC component's but
  for the report (`JavaBoundary.reportsUncaught`, false on every reactor): the initialization
  is the top level as is, the start function's, and an uncaught exception the bare trap.
  Measured 2026-11-26: `rust-calls-java`'s counter as written 2,345 / 1,282 B gzip against
  15,076 / 6,984 as a wasm-GC component, its `indexOf(char)` a `position` ("Exact" above);
  with the `indexOf` written as `==`s 2,040 / 1,075 against 12,988 / 5,913;
  `JavaWitImportTest`'s `Kinds` with its `StringBuilder` a `String`, 2,226 B.
- **Pinned by** `JavaReactorTest.aReactorOfScalarValuesCompilesWithNoGcAndWhatLeavesTheSubsetIsRefusedByName`
  (a JDK against the module under node, no glue; the traps; the refusals),
  `RontoLispCliStreamsTest.aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (a command).

## The tools (2026-11-17, j74)

`rontolisp test` runs a `.java` file's tests ("Tests of a program" below); `rontolisp format`
refuses a `.java` and a `.star` named explicitly (re-indenting is no whitespace-only change
there). The Maven plugin's `compile` goal compiles `.lisp` only: a `.java` file of a Maven
project is javac's, and a subset program compiled to a class of the same name would collide with
it. Its `wasm` goal compiles `src/main/java`'s program to a `.wasm`, which collides with nothing
(`jvm-export.md`, "The plugin's `wasm` goal"); with no main class, the classes `@WitExport`
marks ("WIT exports" above).

## Tests of a program (2026-11-24, l81; JUnit Jupiter from the program's dependency: l93)

**Invariant: `rontolisp test FILE.java` runs the test sources javac and surefire compile and
run, unchanged, on the program rontolisp makes: the tests are FOUND WHILE THE FILE IS
COMPILED and called by generated Java source; JUnit Jupiter is the program's own dependency
compiled from its sources, AssertJ a part the library has under its names; the report is one
shape on five legs; the exit status is the verdict (0; 1 where a test failed or none ran; 2
for a wrong command line). What a run does not run, and what the library's part lacks, is
refused by name where the file is compiled, never skipped.** The user's reason
(2026-11-24): a JDK's green run is no proof of the compiled program, which differs from the
JDK in the front end's semantics, the library and the host.

- **Measured first: what the tree's own suites use** (10 test classes, 2026-11-24): `@Test`
  25, `@ParameterizedTest` 19, `@TempDir` 5, no assertion of JUnit's; AssertJ's `assertThat`
  499, `assertThatThrownBy` 60, `isEqualTo` 306 .. 29 checks at 7 or fewer.
- **Not compiled: the JUnit Platform**: its discovery scans a class path for class files,
  which a compiled program has none of, and a `ServiceLoader` engine calling tests by
  `Method.invoke` would carry the tables of every test class. The front end finds the tests
  as it finds an annotation index's classes ("Annotations read where the program runs").
- **AssertJ answers from the library's part: measured and decided** (2026-11-25, l86; 3.27.7's
  sources jar, 792 files, 163,194 lines). AssertJ's sources
  compile (renamed `org.assertjx`, the measurement's copy only, so that the library's part does
  not answer) and run l81's ten assertions (`probes/Probe2.java` of its artefacts) with the
  JDK's bytes on the four legs. Of the 22 classes of a JDK they import and the library lacked,
  they need `AtomicMarkableReference` and `AtomicStampedReference` (a class literal of each is
  evaluated in the representation of every value), which the library now has; 12 statements
  naming the others (`java.text`'s dates, `java.lang.management`) or Byte Buddy's `Assumptions`
  are warnings and never run. With Byte Buddy on the path (assertj-core's
  `compile` dependency, which `--java-source-dep` brings) the same output: `Assumptions`, whose
  initialization makes a `ByteBuddy`, is lazy and never initialized, as a JDK never loads it.
  Against the library's part, the same ten: JVM class 2,851,896 B / 396,156, Preview 1
  3,732,811 / 269,877 (13.8x), component 3,739,326 / 272,135, a leg's compile 35-46 s / 6.4-7.7
  s, the interpreter 14.1 s / 2.3 s. Of the IR (3,965 forms, 2.31 MB of text / 442 forms, 144
  KB), the class objects' generic types and members (`%class-generic`, `%class-members`: 0.69
  MB, reflection over everything a representation may print) and AssertJ's methods. Of
  l81's 202 failing checks (`probes/Messages.java`), the sources jar answers 102 as AssertJ does
  under a JDK, class and text, where the library's part answers 187: 63 the same text in
  `java.lang.AssertionError` where the JDK run (opentest4j on its class path) throws
  `org.opentest4j.AssertionFailedError`, 8 `NoSuchMethodError` (`Thread.getStackTrace()` in the
  text of a group's check), 29 another text (a stack trace's frames, the opentest4j classes).
  **Decided**: the library's part answers `org.assertj` in a run of tests and a project's
  AssertJ on the source path is not read for it (`JavaClasses.TEST_PACKAGES`): 14x the module
  and 5x the compile for ten checks, and fewer messages AssertJ's own. The sources jar as a
  project's choice: `.todo/m43`.
- **Where the API is.** JUnit Jupiter (l93; the user's decision on the licences:
  [java-frontend-provenance.md](java-frontend-provenance.md), "A test API under its own
  names") is read from the source path as any library ("A library from the source path"):
  `--java-source-dep org.junit.jupiter:junit-jupiter-params:V` brings `junit-jupiter-params`,
  `-api`, `junit-platform-commons`, `apiguardian-api`, `opentest4j`, `jspecify`, of which a
  run reads the first four. AssertJ and opentest4j are the library's, found before any
  source path as a JDK's packages are (`JavaClasses.TEST_PACKAGES`): a class of them it
  lacks is refused by name, and JUnit's own code throws the library's
  `AssertionFailedError`. A JUnit Jupiter class whose artifact no root has is `the class
  org.junit.jupiter.api.Test is not on the source path: JUnit Jupiter is a dependency of the
  program, compiled from its sources (--java-source-dep
  org.junit.jupiter:junit-jupiter-params:VERSION)` (`JavaClasses.junitMissing`), one the
  artifact lacks `cannot find symbol`.
- **JUnit Jupiter's sources compile: measured** (given to the front end, not read). With 6.0.3 the corpus case
  and the tree's four suites print on four backends the same bytes as with l81's part of
  JUnit, the corpus case also with 5.10.5, 5.11.4, 5.14.4, 6.1.3 (interpreter; 5.8.2 has
  two messages of its own). Called alone, of 6.0.3's 312 public static methods of
  `Assertions`, `Assumptions`, `Arguments`, `Named`, `assertTimeoutPreemptively`
  (`Thread.getStackTrace()`) refuses; the `assume*` and `abort` throw the library's
  `TestAbortedException`, a skip ("Skips, `@TempDir`" below). Against l81's part: the corpus case +17% on
  wasm, +24% as a class, a suite calling no assertion of JUnit's the same bytes, the jar 21
  files (26,468 B of source) lighter.
- **What a run does not run is refused** (`JavaTests.check`): with JUnit's own annotation
  types there, it would compile and be skipped (measured: a `@TempDir` field stayed `null`,
  a `@RepeatedTest` never ran, green). Refused where written, in every file whose classes
  run: an annotation of a package under `org.junit.` that `JavaTests.RAN` does not hold
  (JUnit 4's included), an element of one it holds that the run does not read, one on an
  annotation type (a composed annotation, whose methods JUnit runs), a parameterized test's
  parameter of JUnit's type (`ArgumentsAccessor`, `TestInfo`, `Named`), which JUnit makes
  (a `TestInfo`'s display name would show the arguments), an orderer other than
  `OrderAnnotation`/`MethodName`/`DisplayName`, a `RepetitionInfo` given around a test that
  is not repeated (JUnit: a `ParameterResolutionException` of that test).
- **JUnit's rules for a source written twice and a `Named` argument**
  (`doc/en/java/testing.md`), pinned by `p.FifthTest` of the corpus case: measured,
  `@CsvSource("a") @ValueSource("b") @CsvSource("c")` runs a, c, b (javac's container;
  `@Repeatable` since 5.11); a `Named` argument is its payload (`TestSources.object`), which
  a `Named` parameter would get and JUnit fails to convert.
- **The surface** (`cli/TestCommand.java`, `JvmSourceCompiler.javaTests`): the target is the
  file itself (`SourceStandards.javaTests`), so positions and the source tree are the
  file's; `-o` compiles the run with every compile flag. **A directory is the `.java` files
  below it** in path order (`TestCommand.javaFiles`, no `package-info`/`module-info`), every
  one handed to the front end: no text prefilter, since a class with no test annotation of
  its own inherits tests, and a file with none runs nothing.
  **Several files of one tree are one run**, a further file found below the first file's
  tree at the path its package and class spell (`JavaClasses.treeFile`) and run in the
  order given, one not of the tree or with no class of its path's name refused at the first
  file's start. Pinned by the `tests:` case of `java-spec.yaml` with `q/OtherTest.java` in
  `files:`. Measured 2026-11-24 on `SqlTest`, `ScramTest`, `TargetTest`: one program 3.9 s
  on the interpreter against 8.3 s for three runs, its compile 7.5 / 8.9 / 8.9 s (JVM / P1
  / component) against 17.1 / 20.4 / 21.6 s, JUnit's sources and the library being most of
  a compile.
- **The generated Java** (`JavaTests`): a static import of each test class, as a
  `@CsvSource` element may name its constant; a source annotation an INSTANCE
  (`annotationValue`) handed to `rontolisp.internal.TestSources`, which is Java,
  `@MethodSource` and `@EnumSource` read where the file is compiled; an argument converted
  by its parameter's STATIC type, so no class object is made. Generated source reaches what
  its class reaches (`JavaClasses.isGenerated`): a private constant in an annotation of its
  own class is legal Java, and the holder is another class.
- **The report** (`rontolisp.internal.TestRun`): surefire's lines without the times, so five
  legs print the same bytes
  (`Tests run: n, Failures: f, Errors: e, Skipped: s[ <<< FAILURE!] -- in C`;
  `doc/en/java/testing.md`); a throwing `@BeforeAll` / `@AfterAll` is one error of the
  class, a disabled class one skipped test.
- **Pinned against the frameworks themselves** (`testsupport/JavaTestOracle`,
  `JavaSpecE2eTest`'s `tests:` cases): the JDK leg compiles the file with javac against
  JUnit Jupiter 6 and AssertJ 3.27 and runs it on the JUnit Platform with a listener
  printing the runner's shape, so one `stdout:` holds both (the corpus case: 36 tests, 38
  failure messages and their classes); the other legs read JUnit's sources of the JDK
  leg's version (`JavaSpecE2eTest.junitSources`; skipped where they cannot be had). JUnit
  orders a class's tests its own way, ours is the order declared: the oracle sorts a class's
  problems, the case declares its failing tests in the order of their names. Of 202 failing
  checks probed (`probes/Messages.java` of the artefacts), 187 answer AssertJ's class and
  text; the rest carry stack frames, a JDK's class name or an identity number.
- **The tree's suites that run** (`JavaSpecE2eTest#theSuitesOfTheTreesModulesRunUnchanged`,
  four backends): `SqlTest`, `ScramTest`, `TargetTest` of `jdbc-postgresql` and
  `UrlCodecTest` of `servlet-httpserver` (65 runs), green as they are, none failing under
  rontolisp only. **The six others stop at the library or the host, not the runner**
  (measured 2026-11-26): `ServletHttpServerTest` at `java.lang.Process` (`ss` for the accept
  backlog), then `URLClassLoader` and TLS (`SSLContext`, `KeyStore`); `ServletContainerTest`
  and `DiscoveryTest` at Tomcat's and Jetty's classes (`Container.java`) and `javax.tools`;
  `PostgresDriverTest` at Testcontainers; `ConformanceTest` (webmvc-fn-lite) at
  `javax.tools.JavaCompiler`; `KeyValueSessionStoreTest` (with `servlet-httpserver` and
  `rontolisp-java-api` on the source path) at `KeyValueStore.Bucket` not found from the test
  file, then the recursive comparison, and on Preview 1 the `@WitImport` store
  (`.todo/m61`, `.todo/m62`). A program that is no run of tests holds none of it: the lowering changes
  only where `javaTests` is set or a test package of the library's is named
  (`examples/java/shop` 323,921 B, byte-identical across l87).
- **Past one instance a test and the order declared: measured** (JUnit 6.0.3, 2026-11-26;
  pinned by the corpus case's `q/LifecycleTest.java`, four backends against the oracle).
  The class's members are read level by level from the top (`JavaTests.levels`): for each
  class from the class above all down, its interfaces not yet seen (each after those it
  extends), then the class -- so an interface's `@BeforeEach` runs before its implementing
  class's, a superclass's interface's before the superclass's. A method a later class (or
  a subinterface) redeclares is no test. An interface's static `@BeforeAll` runs.
  `@TestInstance` and `@TestMethodOrder` are found on the class, then its interfaces in the
  order named (depth first), then the class above (`inherited`); a `@Nested` class takes the
  enclosing class's orderer but not its lifecycle. Tags (`TestInfo.getTags`): the method's,
  then the class's (the class above's, each interface's, own, in the order written), then
  the enclosing class's, trimmed, invalid ones (`@Tag`'s documented syntax) dropped, first
  kept. A method's display name is `name(SimpleTypes)`, a class's its simple name. Ordering
  happens at run time (`TestRun.ordered`): `@Order`'s value is an expression of the holder;
  `Order.DEFAULT` for none; equal names by the parameters' qualified types (consistent with
  every probe). `PER_CLASS`: the instance made before `@BeforeAll` (its fields' `@TempDir`
  still per test); a constructor that throws is the class's error and NO `@AfterAll` runs,
  static ones neither; a non-static `@MethodSource` runs on it. `@RepeatedTest`: checks in
  the order value, threshold (`Integer.MAX_VALUE`, the default, or `0 < t < value`), blank
  name, each a `PreconditionViolationException` of the test naming the method as
  `Method.toString` does; a run past the threshold still MAKES the instance (its constructor
  runs, and one that throws is that run's error), then is skipped; an abort is no failure;
  `{displayName}` is replaced first. Refused instead of matched: a constructor `@TempDir`
  under `PER_CLASS` or of a repeated test with a threshold (unprobed). Cost (wasm, one test):
  `TestInfo` +6.0 KB (the tag set made in `getTags`, else a `LinkedHashSet` +49 KB;
  `getTestMethod` costs only where called), `@RepeatedTest` +11.1 KB; a run with none of it,
  the two tree suites and `examples/java/shop` byte-identical (`TestRun.repetition` is a
  method of its own for that).
- **Skips, `@TempDir`, closed arguments: measured** (JUnit 6.0.3, surefire 3.6.0, 2026-11-25).
  Surefire counts a skipped CLASS as its direct children (each test, a parameterized test
  one, each `@Nested` class one): `@Disabled` on a class of two tests is `Tests run: 2,
  Skipped: 2`, as is an `abort` in its `@BeforeAll`; an `abort` in `@AfterAll` adds
  nothing. The runner (`TestRun.around(body, tests)`, `skipped(n)`) and the oracle count so.
  A later failure goes before an abort (`TestRun.then`: an abort, then an `@AfterEach`
  throwing, is that error with the abort suppressed). `@TempDir`: one directory per
  declaration (`junit-<digits>` in `/tmp`, `Files.createTempDirectory`), a static field's
  made before `@BeforeAll` and deleted after `@AfterAll`, an instance field's and a
  parameter's around each test, deleted after `@AfterEach`; a lifecycle method's parameter
  gets one too (probed: `@BeforeEach`'s and `@AfterEach`'s differ, as `@BeforeAll`'s and
  `@AfterAll`'s). `cleanup`/`factory` are refused; a `final` field or one not `Path`/`File`
  is a compile error here, an `ExtensionConfigurationException` of the test under JUnit; a
  `@TempDir` parameter before a row's is refused (JUnit: competing `ParameterResolver`s). A `.wasm` reaches `/tmp` only preopened:
  java-spec's wasm legs and the plugin's run `--dir /tmp::/tmp`; without it the class errors
  `NoSuchFileException: /tmp/junit-..`. An `AutoCloseable` argument (a `Named` one's payload,
  extra row elements too) is closed after `@AfterEach`, the last first; a close that throws
  is `JUnitException: Failed to close extension context` caused by the first, later ones
  suppressed (`TestSources.closed`). Deleting directories is an overload of its own, so a
  run with no `@TempDir` reaches no file code. Cost of the closing (try per parameterized
  test, `JUnitException`): `UrlCodecTest` 231,738 -> 238,354 B wasm, `SqlTest` 295,769 ->
  297,171. Pinned by the corpus case's `q/OtherTest.java` (four backends against the
  oracle).

## Declarations and statements past the first slice (2026-11-17)

- **A nested class's name** is canonical (`Outer.Inner`) in Lisp names, binary
  (`Outer$Inner`) for `getName()`; local and anonymous ones (`Outer$1Local`, `Outer$1`) are
  numbered by a walk of the tree in the order written (`JavaClasses.number`), as a compiler
  numbers them, not in the lowering's order.
- **A class of a compact source file is a member of the file's class**, inner unless static,
  a record, an enum or an interface, as a JDK 25 answers: `new A()` in a static method is
  `non-static variable this cannot be referenced from a static context`, `getName()`
  `Compact$A`.
- **An inner class holds its outer instance** in a slot `this$<depth>`, its depth in its
  `extends` chain, so a class's and its superclass's slots never meet; a constructor owing
  an inner superclass its outer instance with neither `o.super(..)` nor `this(..)` is
  javac's `an enclosing instance that contains O.I is required`. **`Name.this` is the
  instance of `Name` itself, lexically around** (`lexicallyAround`), not the nearest of a
  class under `Name`, which would make `Step.this.apply(a)` in an anonymous class of a
  default method of `Step` call itself for ever (YAVI's `Arguments1Validator.this`).
- **`transient`** on a field means nothing (nothing serializes); on a method, a class, a
  constructor or an interface's field it is javac's `modifier transient not allowed here`,
  as `private`, `protected` and `volatile` on an interface's field are. `strictfp` (by
  name) and `I.super::m` are refused.
- **A local read across a local or an anonymous class's boundary is a CAPTURE** (a slot
  `val$<name>$<depth>` set where an instance is made): by value, hence effectively final
  ("The flow" below). Rejected: the class's methods as closures (a method is a `defun`
  taking `this`; dispatch and `super` calls name it).
- **An enum's `values()`, `$VALUES` and `valueOf` are made where a program first names
  them** (`JavaClasses.enumMethod`): `valueOf` throws, and a program naming only `values()`
  holds no exception class (measured: 83 -> 60 forms).
- **A record**'s `toString`, `equals` and `hashCode` are parsed from text the class table
  writes (`JavaParser.synthetic`): `equals` compares a `double` by `Double.compare`,
  `hashCode` is `31 * h + hash` over the components (unspecified by the language; a
  `double`'s `Double.hashCode`). The text names `java.lang`'s classes by their qualified
  names: a record named `Boolean` or `String`, or one whose sibling is (a WIT variant's
  cases, `@WitImport`), would read the bare name as itself.
- **A switch is a `cond` over the selector**, run-on cases under `(if (<= picked i) ..)` in
  a `block`, no `tagbody` (`runOn`), a `null` selector `%java-null-switch` unless a case
  names `null`; `case` would be no faster: it expands to the same chain of `eql` tests
  (measured below).
- **A pattern's binding is a variable of the statement around it**, set where the test
  holds, by an `if` or a `?:` testing it at the head of its branch (`branch`): the truth
  value of `(and (Circle? o) (progn (setq c o) t))` tested again costs a loop of pattern
  tests 9% on wasm and the component, nothing on the JVM; a `let` in the branch measured
  the same (below). Rejected: a `let` per binding nested into the condition (the else
  branch is duplicated or needs a block).
- **What patterns cover** (`JavaPatterns.covers`): a sealed type when every type it permits
  is, a record when its record patterns cover its components together; a switch
  expression, and a statement with a pattern or `null`, must cover, and then ends in
  `%java-no-match`.
- **`this(..)` / `super(..)` after statements**: ahead of the call the instance is refused
  (`early`) but for a field's store and the instance around. A `final` local with a
  constant initializer is a constant variable (`while (DONE)`, a `case` label).

## The flow (2026-11-17, j77)

**What a compiler decides from the flow is decided after the lowering, per class, over the
tree (`JavaFlow`), as a compiler's flow analysis follows its attributed tree:**
reachability (JLS 14.22), definite assignment and unassignment (16), effective finality
(4.12.4), the checked exceptions a body throws (11.2, "Checked exceptions" below). A
top-level class is flowed whole after its lowering, as a compiler types it whole first: an
error of typing in a later member comes ahead of any flow error. A library class is not
checked. Reported as javac 25 answered 41 probes: the first
problem of the first kind (reachability, assignment, exceptions, captures), each kind in
javac's order over the classes and members (`JavaFlow.problems`; a method written ahead of
a constructor first, a local or an anonymous class where the walk around it meets it); a
class with an error of typing gets no flow, one after a flow error is not flowed, so the
first error is all that is reported.

- **The walk mirrors javac's analyzers** (`might be assigned in loop`, `not initialized in
  the default constructor`) over what only typing knows, which the lowering records by node
  identity (`JavaFlow.Facts`). A condition the lowering has not met yet is no constant to
  `exits`, which can only make a statement look like it completes.
- **Rejected: following assignments inside the lowering**: a lambda's body is lowered per
  overload candidate and again for its type, a switch expression twice, initializers once
  per constructor, methods before initializers, so what one pass assigned was neither one
  path nor all of them.

## Type parameters (2026-11-17, j70)

- **Erased, and typed through** (`JavaTypes.memberType`): only the conversions reach the
  forms (a `T` that is `Integer` unboxes where an `int` is wanted); a method's name has its
  erasure. A pattern naming a generic class with no argument takes what the static type
  settles (`JavaTypes.refine`).
- **An override through a type parameter is ONE dispatch entry, no bridge** (`signatureIn`).
  Rejected: bridge functions (a function more per override and a call more; the cast a
  bridge adds, failing on a polluted heap, is stated in `deviations.md`).
- **A wildcard is captured where a member is read through it**, projected back (`upward`):
  `? extends T` takes no value but `null`, `? super T` a `T`; a field written through one
  takes `CAP#1`, named as javac names captures (`JavaTypes.diagnostic`).
- **A type of an inner member class carries the arguments of the classes around it**, after
  its own (`JavaClass.allTypeParameters`): `Stack<String>.Cell`, `s.new Cell()` and
  `new Cell<>()` give `value` the type `String`. Rejected: an `outer` component of
  `ClassType` (every relation would learn a second list; javac's `allparams` is this
  flattening). javac's errors: `cannot select a static class from a parameterized type`,
  `improperly formed type, some parameters are missing`, `type arguments given on a raw
  type`, and against a parameter's bounds (`JavaClasses.withinBounds`; a wildcard not
  checked) `type argument String is not within bounds of type-variable T`.
- **An intersection is a type variable no declaration names** (`JavaTypes.intersection`):
  members and subtyping come from its bounds; `(A & B) x` casts to each in turn; a lub of
  several least supertypes is their intersection, the class first (`Record & S`). A lambda
  cast to one is made for an interface extending each (`JavaClasses.intersection`, named by
  the names joined by `&` so a session finds it again), an instance of each by the tagging
  of "Lambdas" below. javac's errors: `INT#1 is not a functional interface`, `bad
  intersection type target` (a class but `Object`), and for lambdas in a conditional or a
  switch in a cast `lambda expression not expected here`.

## Inference (2026-11-17, j70)

One `JavaTypes.Inference` per candidate: what each type parameter IS (`exact`), is ABOVE
(`lower`: an argument's type) and is UNDER (`upper`: the target); resolved exact, else the
lub of the lowers, else an upper, else OPEN.

- **Order** (`resolve`): the arguments that are no lambda, each lowered ONCE with no target;
  the phases; the most specific; then the lambdas and method references, once the types
  they TAKE are known (`returns`).
- **OPEN variables stand for the inference a compiler finishes from the target**: a
  parameter nothing spoke of converts to and from every type; a call that is an ARGUMENT
  leaves open what only its own arguments said (`flexible`: `best(listOf(a, b))` needs
  `Node<Integer>` where `listOf` alone finds `Leaf`), a parameter only an upper bound speaks
  of too: fixed at that bound, YAVI 0.18.1's `fromMapReversible` is `cannot be applied to
  given types` (`List.of` finds the lub `ReversiblePart<?, ?>`; `Booking` 983,445 B either
  way). Rejected: lowering an argument again with its parameter's type (exponential in the
  nesting depth; the switch expression's two passes are bounded) and javac's joint
  inference of nested calls (JLS 18.5.2; the forms depend on no type argument, only the
  conversions do).
- **An exact method reference says what the function takes** (`exact`, JLS 15.13.1);
  `this::name` is exact in a generic class too, its receiver bound (else
  `PropertyView.of(this::write, ..)` in YAVI's `ObjectInputValidator.Reversible<X>` erases
  `X` to `Object`), one inherited from a generic superclass not.
- **A call that is an argument and fails without a target waits** (`mayNeedTarget`,
  `Late`) for its parameter's type after the inference: with several candidates, tried
  against each, the most specific by subtyping; for a variable arity parameter, against the
  array first only where it lowers there (else `Map.ofEntries(Map.entry("a", s -> s))` is
  `cannot infer type-variable(s) V`); against what the target says through the
  candidate's return type (`tried`; else
  `new PriorityQueue<>(Comparator.comparingInt(p -> p.rank()))` is `Object.rank() is not
  supported`); and a call is resolved once more with its lambda-holding arguments waiting
  (`eager`; failing again, the first error stands). A failure, not a success, marks each,
  so a program that lowered keeps its forms and its cost, and a failing path pays one more
  lowering of the call's lambdas, linear in the nesting depth. Rejected: lowering every
  such argument twice.
- **A lambda for a parameter typed by the call's type variable waits** for it
  (`pick(() -> "p", () -> "q")`); found by nothing, javac's `cannot infer type-variable(s)
  T`. So do a conditional and a switch expression of lambdas, an arm that is no lambda
  lowered as the type (else `cond ? () -> x : null` is `cannot be applied to given
  types`). An open variable is a reference type (`JavaTypes.takes`): no `T[]` is an `int[]`
  (JLS 4.10.3), so between `<T> f(T[])` and `f(Object)` a primitive array picks the second,
  and alone it is `cannot be applied to given types` (`Inference` in the corpus, `which`). Each value given is checked against the target (`conflict`): `List<OPEN>` does
  not hide a `new ArrayList<Integer>()` beside it for a `List<String>`. An array's element
  type is not captured (`lowerBound`).
- **What the collectors needed**, found by probes of
  `collect(groupingBy(.., mapping(.., toList())))` (`Gathered` in the corpus): an argument
  lowered late captured as one lowered early (`deferred`); under captures of one
  `? super T` is `T` (`Inference.resolved`); an exact type told through a declared bound
  (`incorporateExact`: `M extends Map<K, D>`); an open variable of an argument's type the
  parameter it stands at (`tie`: `collectingAndThen(toList(), l -> ..)` gives the lambda a
  `List<String>`), and one met at a `? super T` is `T` wherever else the value's type names
  it (l22: `toMap(M::getName, Function.identity())` under `collectingAndThen`).
- **A type variable in scope is never the one a call finds** (met by YAVI's schema
  example): the inference's variables are the callee's type parameters, so
  `nest(List.of(value), depth - 1)` in `<T> List<T> nest(T, int)` would solve `T` by a type
  made of `T`. A method's type parameters are types of their own in its body
  (`Method.inside`), a `<>` creation's fresh (`diamondArguments`); two captures share the
  variable both are under (`JavaTypes.above`). Rejected: fresh variables for the callee at
  every call (the `Method` keys `reached`, the effects and the names; every reader of its
  types would need the view).
- **A lambda's shape picks among overloads** (its parameter count, whether its body gives a
  value); a body that is a call is asked whether it gives nothing (`givesNothing`) only
  where several candidates remain and the lambda writes its parameter types or has none, as
  the language does. A call applying only by the unchecked conversion of a raw argument is
  told no target and gives the erasure (JLS 18.5.2; "A raw argument, `Stream.toList()`, the
  digits and the members met").
- **The errors are the compiler's first lines**: `incompatible types: inference variable T
  has incompatible bounds` (one type given) or `inferred type does not conform to upper
  bound(s)` (several) -- matched on eight programs, not derived --, `cannot infer type
  arguments for Box<>`, `method m in class C<T> cannot be applied to given types;`, `no
  suitable method found for m(..)`, `reference to m is ambiguous`, `bad return type in
  lambda expression`.

## Lambdas (2026-11-17, j70)

- **A lambda is a function** for any functional interface (`functional`). A raw constructor
  reference to a generic class given type arguments is javac's `invalid constructor
  reference`, with `<>` `cannot infer type arguments`; a method that is not generic ignores
  type arguments written (JLS 15.12.2.1).
- **An anonymous class made with `<>`** extends the type the creation finds
  (`anonymousType`), refused as javac refuses where that is a capture (`cannot infer type
  arguments for G<>`); every non-private method it declares overrides one (JLS 15.9.5;
  `method does not override or implement a method from a supertype`). As an argument it
  waits for its parameter's type (`anonymousDiamond`; with no target it would extend
  `ArrayList<Object>`); one the expected type cannot be by its class is refused before its
  body (`incompatible types: cannot infer type arguments for HashMap<>`), so
  `order(Runnable)` beside `order(Comparator<String>)` is not taken. A creation finding
  another supertype lowers it again (`inferredLowerings`), else the candidate tried first
  (`total(Object)` beside `total(List<Integer>)`) would fix it. Rejected:
  `classes.changed()` (every pass tries the types in the same order, so the passes would
  never settle).
- **Of two candidates whose parameters are functional interfaces, a subtype is the more
  specific** (JLS 15.12.2.5), before the rule of what each returns: else
  servlet-httpserver's `filter(String, Filter, ..)` takes a lambda meant for
  `filter(String, FilterHandler, ..)`. java-spec
  `a-type-argument-is-found-from-the-arguments-the-target-and-the-lambdas` (`checked`).
- **Measured: `functionp` first is kept** (2026-11-17; 20,000,000 calls through one
  interface, the lambda a function / a structure holding it): JVM 0.76 / 1.57 s, wasm
  1.35 / 1.41 s. **A structure only where the program can tell**: an interface's lambdas
  become `Iface%lambda` structures (`taggedLambdas`), the program lowered again, when an
  `instanceof` or a cast asks for an interface some lambdas are and others are not, or when
  they would not run the same body for a method (a default overridden below, one's own
  method another's default), where a bare function would run the wrong default silently.

## Boxes (2026-11-17, j70)

- **A boxed `long` is `(number . :long)`** (a `byte`, `short`, `float` with its keyword;
  `%java-long-p`); no other value of a program is a cons, so `Integer` and `Long` differ by
  `equals`, `hashCode`, `getClass()`, a pattern switch and as `HashMap` keys. Rejected: one
  value for both (j68: `case Integer i` took a `Long`, silently) and a structure in
  `java.lisp` (the pruner keys a bundled structure by name, `.kb/library-defun-pruning.md`;
  a cons needs nothing).
- **Unboxing tests for `null`** (`%java-null-unbox`: `NullPointerException`), a literal
  not. **`==` of two boxes compares the values**: the language promises it from -128 to 127
  and allows it elsewhere (`==` of two strings: "The library" below).
- Measured 2026-11-17, before / after the two (a 1,000,000-element list summed 20 times,
  30,000,000 unboxings): `List<Integer>` JVM 2.47 / 2.75 s, wasm 9.95 / 9.60 s;
  `List<Long>` JVM 9.85 / 10.35 s, wasm 32.90 / 41.14 s, owned by the `long` arithmetic
  (with j72's `long`, `ListLong` JVM 5.14 -> 1.44 s, wasm 28.02 -> 1.35 s); `Fib`,
  `WrapInt`, `Sieve`, `WrapLong` unchanged in time and wasm bytes.

## Warnings (2026-11-17, j86)

A JDK 25 source launcher prints a program's unchecked warnings on standard error before it
runs (it compiles with `-Xlint:unchecked`); so does the front end, byte for byte on the
measured programs.

- **Where**: a `CompileWarnings.note`, never counted (`--warnings-as-errors` is about Lisp
  forms), at most 100 as javac; the interpreter before the program runs, a compile (`-o`,
  JVM and wasm alike) once as it reads the source, the compiled program nothing (as a
  `.class` javac made), a session none. Sorted by file and position (`JavaWarnings`), as
  javac's attribution order came out on every program tried.
- **What**, at javac's caret: `unchecked call to add(E) as a member of the raw type List`,
  `unchecked conversion` (for an argument `unchecked method invocation` at the call),
  `unchecked cast` (none for an intersection, as javac), a raw type's field written,
  `Possible heap pollution` and `unchecked generic array creation` but under
  `@SafeVarargs` (the library's methods carry it as the JDK's do), with javac's `where`
  lines; for a raw argument, "A raw argument, `Stream.toList()`, the digits and the members
  met". `@SuppressWarnings("unchecked")` silences its declaration's span
  (`JavaParser.suppressed`); `"all"` suppresses nothing: JDK 25's javac still warns under
  it (measured).
- **The library follows the JDK where a message names it**: `List` and `Set` redeclare
  `add(E)` and `addAll(..)`, so `raw.add(x)` names `List`, not `Collection`. Not warned: a
  raw value returned from a lambda body, a conditional's arms, a method reference through a
  raw type, an override whose return type converts unchecked; no other category
  (`[removal]`, `[deprecation]` ...) (`deviations.md`).

## The library (2026-11-17, j71)

- **The split.** INTRINSIC: `Object`, `String`, `StringBuilder`/`StringBuffer`, `Number` and
  the five boxes, `Math`, `System`, `IO`; SOURCE: everything else, a `native` method a form
  (`JavaIntrinsics.NATIVES`). An intrinsic member is a form of the host where the host has one
  and Java in `rontolisp.internal.Support` where it throws a library class that is none of
  `RAISED` or walks text (`Support.appendRange`): one more class in `RAISED` costs every
  program with a `catch`. An intrinsic class implements a source interface (`Comparable`,
  `CharSequence`, `Appendable`) and has the members naming a source class once a program names
  it (`Library.implemented` / `meets`), looked for by name first (`JavaIntrinsics.named`) so
  that `"abc".subSequence(0, 2)` resolves in a program naming no `CharSequence`.
- **Measured both ways before choosing** (`ArrayList` as an adjustable vector, j68, against
  Java over `Object[]`, ns an operation): wasm `add` 11 / 46, `get` 17 / 24, the enhanced
  `for` 16 / 50 to 65, `set` 32 / 35; JVM `add` 66 / 75, `get` 5 / 6, `for` 6 / 16 (34 with
  every loop in one `main`: the JIT, not the form), `set` 10 / 7; the same shapes hand-written
  in Lisp cost the same. Source was chosen: only it finds a key by a program's
  `equals`/`hashCode`, lets a program's class be a `List` or an `Iterable`, and keeps the JDK's
  order. Found with it: a Lisp loop of `(setf (aref b i) (aref a i))` is 56 ns an element on
  wasm, so `System.arraycopy` is `replace`; the run-time library stores into a non-string
  vector with `%aset`.
- **Lowered as far as the program reaches** (`lowerReached`): of the source classes only the
  methods a pass CALLED, the static initialization of the classes it USED, and in a dispatcher,
  `%string`/`%equals`/`%hash`, `%class-name` and an interface test only the classes it MADE an
  instance of (`made`; a first instance lowers the program again); the sets persist over the
  passes. A structure is emitted for a made class, the classes above it and one whose predicate
  or slot the forms name. A program's own classes are lowered whole.
- **A missing member of a library class is refused by name** (`Random.nextExponential()`),
  never `cannot find symbol`. **Decided: a class we do not have never falls through to the
  host's.** Probed 2026-11-17: `java:call` of `TreeMap.put` with a boxed `long` fails on the
  interpreter and the JVM (`LispCons cannot be cast`), and wasm has no `java:`; the front end's
  values are not the host's objects, and such a program would not compile to wasm.
- **`HashMap` keeps the order a program sees**, a JDK 25's
  (`a-hash-map-finds-a-key-by-equals-and-keeps-the-order-a-program-sees`): the hash folded with
  its upper half, masked by a power of two doubling past three quarters, the table made at the
  first `put` (16, or the power of two above the capacity asked); a chain in the order put,
  `compute`/`computeIfAbsent`/`merge` putting AHEAD; a ninth key of a chain (an eighth for those
  three) doubling a table shorter than 64; `putAll` and the copy constructor sizing for
  `ceil(n / 0.75)`, `HashSet(Collection)` for `max(n, 12)`. Not kept: the tree a longer chain
  becomes in a table of 64 or more (below). `Hashtable`: 11 slots, `2n + 1`, a key put ahead of
  its chain, read from the last chain; a copy 11 slots up to five mappings, two per mapping
  above (the one size the JDK's order fits, 2 to 64;
  `a-deque-an-optional-the-utilities-and-the-older-classes`). An unseeded `Random()` need only be
  unlikely to repeat (the specification): ours steps a counter by 2^64 over the golden ratio,
  the clock mixed in.
- **`LinkedHashMap` is a second chain through `HashMap`'s nodes**, through two hooks of
  `HashMap`'s it overrides: measured before choosing, they cost a program with no
  `LinkedHashMap` 39 B of wasm and no time; rejected, a list beside an untouched table, whose
  `remove` must find a key's cell, so every member of `HashMap` would be written again over
  cells. In access order and for `removeEldestEntry` a member a JDK counts as reaching a key
  compares the size before and after (grown: the eldest is asked for; not: the key moves to the
  end); what reaches is a JDK 25's (`computeIfAbsent` answering `null` reaches nothing). Pin:
  `the-linked-collections-keep-the-order-their-elements-came-in`.
- **`==` of two strings is `eq`**; in a program making one, two constants of a text are one
  object (`%java-intern`), the one `intern()` answers. Each is set by a top-level form: the
  corpus's 2,296 inside the initialization form took its JVM method to 65,740 B, past the
  65,535 a method holds ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
  `new Object()` is `(make-array 0 :fill-pointer 0)`: no other value is a non-string vector
  with a fill pointer.
- **`String.format`** is `%java-format` over the program's `%string` ("java.util.Formatter and
  the conversions a format lacked"); `%f`/`%e` round the SHORTEST digits half up, as a JDK
  (`0.15` at one place is `0.2`). `Double.parseDouble` is exact integer arithmetic then
  `%java-nearest-double`; `doubleToLongBits` is `integer-decode-float`, which loses the sign of
  -0.0 (tested by the reciprocal). `%h` is a branch of `java.util.Conversions.more`: as an arm
  of `%java-format` it was 401 B in every program that formats.
- **`Math` is `%strict-math`**: the same bits on every backend; a JDK's may differ in the last
  digit. `System.exit` is `%host-exit` after `finish-output` (the interpreter has no `uiop` in
  a Java program).
- **The library is Java a JDK compiles** (j79, decided by measuring): javac of JDK 25 takes it
  as a patch of its own `java.base` (`--patch-module java.base=<library>`) with no error -- a
  `native` member and a raw type are Java. The front end does not check the library's accesses
  across packages (javac found a package-private member of `Charset` reached from `java.io`),
  hence `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles` (3 s).
- **A long chain of a `HashMap`: measured, not written** (j79). Under JDK 25 an 11th key of one
  hash code in a table of 64 makes the bin a tree whose root moves to the chain's head, and the
  walk departs from ours; `Double` keys of few digits (their hash codes in the high bits) or a
  `hashCode` of `x ^ y` get there, string and `int` keys never did. The order inside a tree
  comes from the JDK's red-black insertion, its tie-breaks (the identity hash among them) and
  where it links a node: no part of the API, so only derivable from outputs -- the provenance
  rule forbids writing it from the JDK's source. **Decided 2026-11-18 (j87): not derived.** It
  first departs for `Float` keys `i * 0.5f` at 25 keys, `Double` keys at 176 (`i * 100.0`) to
  1,800 (`i * 0.01`), `Long` keys `i << 32` not within 600, and moves with every put: matching
  it is the JDK's tree per bucket through every put, remove and resize, some 250 lines in every
  program with a `HashMap` (a list program is 3.7 -> 25.6 KB for less), for an order the API
  leaves open and keys with no `compareTo` lack even between two JDKs. `deviations.md` names
  the `Float` and `Double` keys.
- **Regular expressions run on the engine the Clojure front end runs on**
  ([regex-engine.md](regex-engine.md): `eval/regex.lisp`, written from the `Pattern`
  documentation and a JDK's answers).
  `Pattern`, `Matcher`, `MatchResult` and `PatternSyntaxException` are Java source whose
  `native` members are the engine's functions; a refusal comes back as a value,
  `(:refused text index)`, for the source to throw (`PatternSyntaxException` holds fields a
  run-time `%java-raise` cannot make). `Pattern.compile` and `String.matches` / `replaceAll` /
  `replaceFirst` / `split` are lowered where called, so the Unicode tables travel only with a
  pattern that may read them (`regexProviders`). A split by a constant text with no operator,
  or one escaped (`"\\."`), needs no engine (`%java-split-text`): `"to be".split(" ")` is
  66,664 B of wasm, by a pattern 251,975 B, by one computed at run time 317,580 B. Positions
  count characters, as `length` and `charAt` do. Pin:
  `regular-expressions-run-on-the-engine-the-front-ends-share`.
- **`appendReplacement`** as a JDK's, measured: a malformed replacement appends nothing; a
  group number the pattern lacks appends the input before the match and the replacement up to
  the reference, then throws, moving nothing on; a match appended twice is the
  `IndexOutOfBoundsException` of its range. A `Matcher`'s region and `hitEnd` / `requireEnd`:
  [regex-engine.md](regex-engine.md). Not there: `usePattern`, `namedGroups`.
- **A program's class named as a `java.lang` source class** (`Iterable`, `CharSequence`,
  `Comparable`, `Appendable`) is refused once the program reaches the library's, so their Lisp
  names stay simple: none of the repository's 156 Java programs and 72 corpus cases declares
  one (counted 2026-11-18).
- **A JDK 25's grids** (the j98 probes): a parse's refusal names a radix that is not ten
  (`Support.parseable`); `parseUnsignedInt` refuses at the digit passing 2^32;
  `Map.entry(..).setValue` says `not supported`.
- **An intrinsic member whose `String` may be `null` says so** (`Builder.nullable()`:
  `IO.readln`, `System.getProperty`); any other's text is concatenated as it is. Unmarked,
  `"x" + IO.readln()` at the end of the input is `x` on wasm and an error on the JVM, where a
  JDK writes `xnull` (the last line of
  `the-standard-input-is-read-as-bytes-and-through-a-reader`).
- **`Character`'s tests are the Unicode tables' on every backend.** The host's `alpha-char-p`
  and `digit-char-p` are ASCII-only on wasm (`.todo/269`): over them `isLetter('é')` was
  `false` on wasm alone, and `isUpperCase` meant "has a mapping" (`ª`, `ǅ`). The tests read
  the regex engine's table (`%regex-uni-combo`, a running JDK's answers:
  [regex-engine.md](regex-engine.md), "Tables") through `%java-char-combo`, the first 256 code
  points kept in a vector. Measured: a program counting letters 12,538 -> 42,124 B of wasm;
  10,500,000 `isLetter` calls 0.68 -> 0.99 s on wasm (4.37 s without the
  vector), 18.6 -> 34.8 s on the interpreter; a program calling none holds none. Not on the
  table: `isWhitespace`, `toUpperCase` / `toLowerCase` (the host's fold is uniform); `digit`:
  "A raw argument, `Stream.toList()`, the digits and the members met".
- **`System.getProperty` and `Runtime` answer what every target can**: the separators and the
  encodings, `null` for any other key; one processor; `gc` is nothing; `totalMemory` and its
  kind refused. `System.getenv` is `%host-getenv`: the host's variable on every backend, a
  `.wasm`'s those its host hands it.
- **`java.time.Duration`** (`java.time` is `java.base`'s, so in `LIBRARY_PACKAGES`), from the
  API documentation and a JDK 25's answers
  (a grid of 3,321 lines, a JDK 25's on the four backends). `hashCode` is
  `Long.hashCode(seconds) + 51 * nanos`, no part of the API: read off the probe. Not there:
  `dividedBy` (`.todo/k95`). The rest of `java.time` costs a program of a `Duration` nothing
  (155,029 B of wasm). `Thread.sleep` of a negative `Duration` is no sleep, of zero one an
  interrupt ends (measured).
- **`java.text.MessageFormat`**, Java source with no `Format` above it, takes argument indexes
  with no format type; a type is refused by name when the pattern is read (`deviations.md`).
  What the specification leaves open -- the refusals' words (`negative argument number: `), the
  largest index (10,000), the quotes `toPattern` writes, how a number with no type prints -- is
  a JDK 25's answers to a grid (printed by the four backends but for the refused types and `1e23` / `2e23`). A number with no
  type is what a JDK's English `NumberFormat` writes: an integral box or atomic grouped by
  three, any other `Number` its `doubleValue` half to even at three decimals of
  `Double.toString`'s shortest digits; from 2^53 to 2^63 a JDK's whole-number digits with the
  last 0 (to 2^58), 1 (to 2^61) or 2 rounded away. Exact below 2^63 (200,000 values); above, a
  JDK's older algorithm differs from the shortest digits in 1 of 100,000 random doubles, 6 of
  2,602 `d*10^p` (`1e23`) and 173 of 7,671 `d*2^p` (`deviations.md`). A refused `applyPattern`
  keeps the old pattern (a JDK's keeps a mix). Cost: one message is 154,047 -> 175,771 B of
  wasm against `String.format`, 11 KB of it the double path (a digit string with `insert` was
  5.9 KB more). Pins: `a-message-format-puts-its-arguments-in-a-pattern`;
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (a format type).
- **`java.text.Normalizer`** is Java source over `%unorm-normalize`, shared by the front ends
  ([unicode-normalization.md](unicode-normalization.md)). With it YAVI's classic example
  compiles from the unpatched coordinates (`--java-source-dep am.ik.yavi:yavi:0.18.0`) and
  prints a JDK's lines on the four backends; YAVI stays unchanged, its scan below the form's
  first code point making each call the cost of a `charAt` loop. Pin:
  `a-normalizer-puts-a-text-in-the-four-forms`.
- **`java.util.UUID`** is two longs, `compareTo` signed as specified. Past the canonical form
  the specification gives, `fromString` is a JDK 25's answers: five signed hexadecimal longs
  (a leading `+`, any Unicode digit) each cut to its field, so `1-1-1-1-1` is a UUID; a bad
  part is `Long.parseLong`'s refusal of a range (`Error at index N in: "part"`), a digit
  outside ASCII read as `Character.digit` reads it (`deviations.md`). Refused by name:
  `timestamp`, `clockSequence`, `node`. Pin:
  `a-uuid-is-two-longs-read-from-its-text-as-a-long-is`.
- **Members a line of Java answers**: `Character.toChars` into an array writes the low
  surrogate first, so a short array keeps the high one's place; `codePointCount` of a range is
  its length (a string's elements are code points). `Members.java` beside `Dur.java`: 866
  lines, a JDK 25's on four backends. Still refused: `.todo/k95`.

## The sorted collections (2026-11-20, k47)

**Invariant: a `TreeMap` is a tree balanced as AVL trees are; what a program sees of it --
the order, every answer and refusal of the map and of its views, what each spliterator
reports -- is a JDK 25's, learned from probes; which pairs of keys its comparator is asked
about is the tree's own.** Written from the API documentation and JDK 25's answers
([java-frontend-provenance.md](java-frontend-provenance.md)).

- **The tree** relinks the next node into a removed node's place, never taking its key, so an
  entry a program holds and an iterator's next node survive a removal. As a JDK's, a put into
  an empty map compares the key with itself (refusing `null` and a key with no order), and a
  copy of a sorted map compares nothing (`Calls.java`).
- **A view is the tree and its bounds** (`TreeMap.View`, one class for the four kinds; its
  refusals `Bounds.java`, its spliterators `SplitGrid.java`). A JDK keeps one `keySet`,
  `entrySet` and `descendingMap` of a map (`==`); here each call makes one, as `HashMap`'s do
  (`deviations.md`): three fields in every `TreeMap` for an identity no probe compared.
- **`ConcurrentSkipListMap` is a `TreeMap`, or a view of one, under the map's monitor**
  (`java.util.concurrent.Ordered`; no skip list), its views refusing as a JDK's, not as a
  `TreeMap`'s (`inconsistent range`). Its iterator reads the next entry by the key it gave
  (`higherEntry`), so it never refuses and gives a key put between the two after a removal,
  which a JDK's, following the removed node's link, passes by (`deviations.md`; the corpus
  case keeps clear).
- **Measured and not made**: a JDK's unmodifiable map entry refuses `setValue`, and an
  unmodifiable view's iterator `remove`, with no message, where these say `not supported` and
  `remove`: the JDK's words were 2,282 B of wasm in a program walking an unmodifiable map's
  entries (a second entry class in the dispatchers), 301 B walking an unmodifiable set.
- `AbstractSet.removeAll` walks the smaller of the two, as documented: a set under a
  case-insensitive order loses `A` to `removeAll(List.of("a"))`.
- **Measured**: a program naming none is unchanged to the byte; 200,000 puts and gets, a walk
  and 100,000 removes take JDK 25 196 ms, the JVM 369, wasm 739 (a `HashMap` 46, 209, 270);
  `Stress.java` and `Grow.java` (seeded) print a JDK's lines on the four backends.
- Pins: `a-tree-map-keeps-its-keys-in-order-and-a-view-is-a-range-of-them` and the sorted rows
  of `a-spliterator-reports-what-a-jdk-reports-of-each-collection-and-a-stream-is-made-of-one`.

## The enum collections (2026-11-20, k47)

`EnumSet` is a `long` word for each 64 constants, a bit by ordinal; `EnumMap` a slot for each
constant. Read off a JDK 25 (`Enums*.java` of the k47 artefacts): a set's iterator reads a word
when it comes to it; a map's iterator never refuses; a map's entry is its slot, which, set
again after its key left the map, holds the value uncounted, where a JDK's `size()` counts down
to `-1`. Hash codes are the constants' identity numbers ("The decisions"). Pin:
`an-enum-set-is-a-bit-of-each-constant-and-an-enum-map-a-slot`.

## The sequenced collections (2026-11-20, k55)

**Invariant: a list, a deque, a linked or sorted set and a linked or sorted map are a
`SequencedCollection`, `SequencedSet` or `SequencedMap`, and `reversed()` is a view of it in
the other order through its own public members; what a JDK 25 answers through such a view --
each index and refusal with its words, what a walk sees of a change -- is the corpus's.**
Written from the API documentation (the defaults' stated implementations) and JDK 25's
answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **The defaults**: an entry `firstEntry` .. `pollLastEntry` answers is an
  `ImmutableCollections.Pair` (`setValue` says `not supported`, as a JDK's), in `SortedMap`'s
  defaults too: a `SimpleImmutableEntry` refuses with no message. A map's sequenced views ask
  it for its view at every call, as a probe counted a JDK's doing.
- **A list's view** (`ReversedList`) checks an index against itself before the list is asked
  and walks the list's `listIterator` back, so a change behind it is the list's
  `ConcurrentModificationException`; `LinkedList.reversed()` is a `LinkedList` holding no
  cell; a deque's view calls the member of the other end.
- **`LinkedHashMap`, `LinkedHashSet`**: `putFirst` / `putLast` move a key held to that end, a
  change unless it is there; a new key is put, moved, then asked about as eldest, so
  `putFirst` into a full LRU map removes that key (a JDK's answer). A set's view's spliterator
  has no `ORDERED`, as a JDK's. A program calling none of them is unchanged to the byte.
- **A sorted set or map that is not navigable** (`unmodifiableSortedSet(..).reversed()`) is
  walked live, seeing what was added ahead. Its refusals are a JDK's, read off every bound of
  three ranges (`Seq3.java`), a JDK's own quirk among them: `tailSet` of a range keeps only the
  end it stops before (`headSet(3).tailSet(4)` is `[5, 4]`).
- **A JDK's, met beside the views** (`Fixes.java`): an unmodifiable collection or map refuses
  `removeIf`, `compute` and their kind even where nothing would change; `HashMap.newHashMap`
  and its kin ask for `(int) Math.ceil(n / 0.75)` (a JDK's table order at 6 to 97 mappings);
  the concurrent queues' iterators remove from the queue the element at its place, else the
  first equal one (`java.util.concurrent.Copied`, `deviations.md`).
- **Measured** (wasm bytes): a list printed reversed 133,376 -> 143,015, the view in place of
  a copy; iterating a `ConcurrentLinkedQueue` 63,954 -> 66,243, a second iterator class making
  the loop's calls dispatched.
- Pins: `a-sequenced-collection-has-two-ends-and-a-reversed-view`;
  `JvmLispCompilerTest#aCycleTheRootDoesNotCloseDispatchesOnAMemberIndex` (the view's `next`
  and `previous` call each other's: a JVM tail group of members of two arities,
  [jvm-self-tail-calls.md](jvm-self-tail-calls.md)).

## The collections of Collections, a bridge's cast and PriorityQueue (2026-11-20, k60)

**Invariant: the collections of no element, of one and of n copies of `Collections` are
classes of their own over the abstract collections, so a change is refused where it would
change something and answered where it would not; the list of `List.of` refuses every change
and a null asked for; an arm of a dispatcher whose body takes a narrower type than the method
called tests the argument as a JDK's bridge method casts it; `PriorityQueue` is a binary
heap whose order is a JDK's.** Written from the API documentation and JDK 25's answers to
probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **`Collections`**: the empty ones are one object each, their refusals a JDK's (`Imm.java`),
  their reversed views the changing ones, as a JDK's. What several answer alike is a member of
  a class above them (`Few`, `FewSet`, `FewMap`), one arm of a dispatcher: the five classes'
  spliterators as five arms were 1.3 KB of YAVI's schema example. `Objects.requireNonNull(o)`
  is `native`, `(or o (%java-npe))`, so no exception object is made where nothing is null.
- **`List.of`'s list** (`ListN`): `get` is the array's read, the JDK's
  `ArrayIndexOutOfBoundsException` for 0 and 3+ elements (577 -> 240 B of wasm); the words of
  a list of one or two, 440 B more in every `List.of` program, are not made.
  `CopyOnWriteArrayList.reversed()` hands the list the index of the other end unchecked, as a
  JDK's (`get(-1)` of six: `Index 6 out of bounds for length 6`).
- **A bridge's cast** (`JavaLowering.bridged`; `Integer.compareTo(Integer)` for
  `Comparable.compareTo(Object)`): anything but `null` or an instance raises the
  `ClassCastException` of a failed cast (`%cast-refused`); a call straight to its one body
  tests nothing, nor a lambda. A program sorting strings: 157,884 -> 158,398 B of wasm (three
  arms). A dispatcher made for the one body too was 2.6 KB in a program putting into an
  `EnumMap` through `Map`: not made.
- **`PriorityQueue`**: an array (Williams 1964; Floyd's 1964 sinking for a collection in no
  order and for what a bulk removal leaves). Which of equal elements goes where, which pairs
  the comparator is asked about and the order of a walk that removes are a JDK's answers
  (`PQOrder`, `PQWalk`, `PQSeeded`: every line a JDK's on the four backends); `removeIf` of
  nothing is a change; with no comparator even the first element added is cast to
  `Comparable`, one of a collection not. Measured 2026-11-21 (`Bench2.java` of the k82
  artefacts): 1,000,000 adds and polls of `Integer`s 1.41 to 1.47 s on wasm, 1.49 to 1.71 s on
  the JVM, 0.32 s under a JDK -- 4.4 times a JDK's, the JVM backend's own ratio: nothing about
  the heap is slower on wasm; a guest profile puts a third of the samples in a 64-bit
  subtraction with an overflow test.
- **Measured and not made** (wasm bytes): `Set.of` and `Map.of` refusing a null asked for
  (some 300 B in a program asking), `Map.of`'s keys and values as `AbstractMap`'s views (500
  B), `Arrays.asList`'s iterator of its own for the JDK's words `remove` (0.9 to 1.4 KB).
- **Left** (`deviations.md`, `.todo/k95`): `List.of`'s iterator's `remove()` before `next()`
  is `IllegalStateException`; `Map.of`'s views refuse a change that changes nothing; the empty
  `List.of()` is a new object; an `AbstractList` iterator past the end has no cause (a `catch`
  there would be in every program walking such a list); a map's views are new at each call.
- Pins: `a-deque-an-optional-the-utilities-and-the-older-classes` (`immutables()`,
  `priorities()`), `JavaLoweringTest#typeParametersLambdasAndBoxes` (the bridged arms).

## A raw argument, `Stream.toList()`, the digits and the members met (2026-11-21, k71)

**Invariant: a call that applies only by the unchecked conversion of a raw argument is typed
as JLS 18.5.2 and javac type it -- the type it is wanted as says nothing of its type
parameters, its value is the erasure of the type returned -- and warned of as javac warns;
the list of `Stream.toList()` and of a gatherer's window is a list of `List.of`'s kind that
holds nulls; `Character.digit` is one rule on every backend, Unicode's decimal digits read
from a list of their zeros.** Written from the API documentation, the JLS and JDK 25's
answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **A raw argument** (`JavaLowering.resolve`): the target is not told -- `Object` would make
  the `T` of `Collections.max(raw)` an `Object`, no `Comparable<? super T>`; a type parameter
  is what the other arguments found, else its first bound that is not `Object`, as javac's
  refusal of `int x = call(raw)` names it (`Types.java`). `Collections.max` / `min` declare
  `T extends Object & Comparable<? super T>`, as the API does (a warning's `where` line names
  both bounds).
- **Its warnings** (`JavaWarnings`) are javac's, read off `Warnings.java`:
  `unchecked method invocation` where the call applies by the conversion,
  `unchecked conversion` where a parameter still needs it (`? extends Object` is `?`, JLS
  4.5.1); a value's conversion comes after what its expression said.
- **`Stream.toList()` and a gatherer's window** are a `ListN` of the kind `NULLS`. The streams'
  classes cannot name a non-public class of `java.util` (javac compiles the library as
  `java.base`), so `Flow.listed(Object[])` is a `native` member calling
  `ImmutableCollections.listed`: no member a program could name.
- **`Character.digit`** (`%java-digit`): ASCII and the fullwidth Latin letters by base 36, a
  Unicode decimal digit (Nd) by its distance from its zero, the 75 zeros above ASCII the
  characters of a string (`%java-unicode-digit`, pinned to the running JDK by
  `JavaLoweringTest#theLoweringTable`); the host's `digit-char-p` knows Unicode's digits on the
  interpreter and the JVM, none on wasm. A number's text and a format's control read ASCII
  digits only, as a JDK. `Sweep.java` of every code point gives a JDK's `digit` and
  `toTitleCase` on the JVM and wasm. Measured at first (k71): the zeros a quoted list, 670 B of
  wasm and its scan 590 (+1,538 in a program parsing an int), where a string of bounds decoded
  into a vector was 3,166, a vector literal +3.8 KB, the zeros as a string +2.6 KB. **Measured
  again 2026-11-26, the string against the list** (the list conses 75 cells at each call, and
  `--no-gc` has none): a command parsing ints, longs and a double -1,604 B of JVM classes, -215
  of wasm, -171 of component; one whose only string work is `Character.digit` -1,274 JVM, -294
  wasm, +271 component; a `--no-wasi` reactor of `parseInt` 12,083 -> 11,789, of
  `Character.digit` alone 3,982 -> 4,529 (the string's indexing is its only one). **Not
  made**: `getNumericValue` of the numbers that are no digit (`Ⅷ`, `²`, `½`; 4 KB as text) --
  in every program calling it, and programs call it for ASCII digits.
- **Members** (`Strip.java`, `Title.java`): `translateEscapes`' refusal is a JDK's (U+0000
  named after a final backslash); `toTitleCase` is the uppercase but for the digraphs `Ǆ` ..
  `ǌ`, `Ǳ` .. `ǳ` and Georgian's Mkhedruli, which has none.
- Pins: `the-unchecked-warnings-are-printed-before-the-program-runs` (a raw argument),
  `a-deque-an-optional-the-utilities-and-the-older-classes` (`toList()`'s list),
  `a-gatherer-is-a-stage-holding-what-it-pushes-until-it-is-asked` (a window),
  `the-boxes-parse-print-and-compare-and-math-is-strict` (the digits),
  `a-string-has-its-everyday-surface-and-two-constants-of-a-text-are-one` (the members).

## The copies of `copyOf` and the halves of a surrogate pair (2026-11-21, k82)

**Invariant: `List.copyOf`, `Set.copyOf` and `Map.copyOf` answer a collection of `List.of`'s,
`Set.of`'s and `Map.of`'s own kind as it is, as a JDK does; where text is made of UTF-16 units
-- `new String(char[])`, a concatenation, a builder's `toString` -- a high surrogate followed by
a low one is the one character the two spell, on every backend, as a JVM string makes it;
`chars()` answers the units `toCharArray` does.** Written from the API documentation and JDK
25's answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **`copyOf`** keeps a list of `List.of`, `List.copyOf` or `Collectors.toUnmodifiableList` and
  copies a part of one, a list holding nulls (`Stream.toList()`) and a view (a `Map.of` map's
  keys), as a JDK (`CopyOf2.java`).
- **Measured and not made: the empty `List.of()`, `Set.of()` and `Map.of()` as one object each**
  (a JDK's are): every `List.of` program +150 to +500 B, `SzAsList` +812 -- a global's value set
  holds its default ahead of the initialization, so the fold keeps a cast in every `toArray` a
  `List.of` list reaches.
- **The halves of a pair** are joined where each backend makes a string (`Seams.java`), on wasm
  only in a module holding this front end's run time
  ([characters-code-points.md](characters-code-points.md), "The two halves of a surrogate pair
  meeting"); a builder counts them as two, as a JDK's, its `toString` as one. Rejected: a join
  at every change of a builder (+5 KB in every builder program), `_str_cat` writing each seam in
  place (+904 B) or without a cast of a string piece (+140 B: the fold met an i31), the joins in
  every module (every language paid them).
- **`chars()`** is `toCharArray`'s units (`Chars.java`): code points lose a supplementary
  character's high bits under `(char)`.
- **Left** (`.todo/k95`): written to the standard output, a lone surrogate is `?` and two halves
  printed apart are one character on the interpreter and the JVM (the host's encoder holds a
  high surrogate), as under a JDK, three bytes each on wasm (`PrintHalves.java`); `getBytes` of
  a lone one is three bytes on every backend, `?` under a JDK.
- **Measured and not made: a `StringBuilder` as a `Comparable`** (a JDK's since 11): +385 B in
  every program dispatching `compareTo` that makes a builder.
- Pins: java-spec `a-deque-an-optional-the-utilities-and-the-older-classes` (`immutables()`),
  `a-string-has-its-everyday-surface-and-two-constants-of-a-text-are-one` (`halves()`).

## A joiner, a tokenizer, a bit set, a scanner and a char buffer (2026-11-21, k72)

**Invariant: `StringJoiner`, `StringTokenizer`, `BitSet`, `Scanner`, `Readable`, `Buffer` and
`CharBuffer` are Java source answering what a JDK 25 answers -- each refusal's words, `BitSet`'s
`size()`, when a `Scanner` reads more of its input and what its text says -- and a `Scanner`
whose program names no pattern holds no regular expression.** Written from the API documentation
and JDK 25's answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **Read off the answers**: a `StringJoiner` merged with itself doubles and refuses the prefix's
  null first, then the delimiter's, the suffix's (`Joiner.java`); a `StringTokenizer`'s null set
  is refused by `countTokens` only where it has a character to look at (`Tokens.java`);
  `BitSet.size()` (`BitSize.java`). `BitSet.stream()`'s spliterator is the subset's
  (`ORDERED|SIZED|SUBSIZED`, no split; `deviations.md`), a JDK's `DISTINCT|SORTED`, split at the
  middle of the set bits' span (`BitSplit.java`).
- **`Scanner`** holds its input as a `String`, a `Reader` read in pieces of
  `max(1024, what is held)`: `match()`'s and `toString()`'s positions are in it, a JDK's in its
  own buffer (the same up to 1024 characters; `deviations.md`). Its default delimiter is no
  pattern (`Character.isWhitespace` is `\p{javaWhitespace}`); a member taking a pattern's text
  is `native`, the pattern compiled where it is called (`JavaIntrinsics.scanning`):
  `useDelimiter(",")` is 311,455 B of wasm (`Pattern.compile(",")` and a matcher alone 286,623),
  a million ints 146,358, 265,496 with one member parsing every kind of number (`java.math` with
  it), so each kind has its own.
- **Read off the answers** (`ScanReads.java`, scripted pieces; `Scan1.java` .. `Scan9.java`):
  when each member reads more (the fast path where `\p{javaWhitespace}+` would); any decimal
  digit a digit of every radix, one past it refused with the parse's words
  (`For input string: "9" under radix 8`), a token the grammar refuses with none; the locale's
  `\u221e` taken, then refused; a refused token left, the position past the delimiters; what a
  `hasNext` keeps (`Scan5.java`); `tokens()` and `findAll` refusing with
  `ConcurrentModificationException` once the scanner was asked anything else; a search's
  transparent, non-anchoring bounds (`Scan8.java`); `toString()`'s text (`Scan3.java`).
  `match()` after a number has no group, a JDK's its pattern's (35 for an `int`, 132 for a
  `double`: `deviations.md`).
- **`System.in` closed is at its end**: a `Scanner` over it again has no token, as under a JDK,
  whose read throws `IOException("Stream closed")`; not thrown, measured: a program reading
  `System.in.read()` 21,588 B of wasm, 53,180 throwing (the exception classes).
- **`CharBuffer`** (`Chars.java`): a wrapped `String` has no array
  (`UnsupportedOperationException`), another `CharSequence` is read live; the refusals' words
  (`newPosition > limit: (9 > 8)`) and `hashCode` are a JDK's.
- **Hexadecimal floats** in `Double.parseDouble` and `Float.parseFloat` (`%java-read-number`). A
  number's size counts from its first digit that is not `0` (else `0` times 500 then `1` is
  `Infinity`). `DecGrid.java` (8,000 seeded decimal texts), `HexGrid.java` (6,000 hexadecimal,
  33 at the formats' ends): a JDK's bits on the four backends. Rejected: radix 16 as a function
  of its own (+5,169 B of wasm), over `(expt base power)` (+13,247 B: the generic `expt` brings
  the complex numbers).
- **A million ints from `System.in`** (10 MB, `bench/` of the artefacts): a JDK 2.16 s, the JVM
  class 2.35 s, wasm 7.84 s, 5.63 s of it reading through an `InputStreamReader` (a character a
  host call: `.todo/k91`); `BufferedReader` and `StringTokenizer` 0.95 / 2.81 / 13.62 s.
- **Left**: `java.text` `.todo/k87`; `BigInteger`'s primes `.todo/k90`; the concurrent queues on
  `PriorityQueue` `.todo/k21`. A `Scanner` of a file: "Files" below.
- Pins: java-spec `a-scanner-reads-tokens-numbers-and-lines-of-a-text` (every kind and radix,
  finds), the `standalone:` case `a-scanner-reads-the-standard-input` (a contest's input, the
  closed `System.in`), `joiners`, `tokens` and `bits` in
  `a-deque-an-optional-the-utilities-and-the-older-classes`, the hexadecimal and leading-zero
  parses of `the-boxes-parse-print-and-compare-and-math-is-strict`;
  `JavaLoweringTest#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition`.

## The buffers (2026-11-23, k88)

**Invariant: `ByteBuffer`, `ByteOrder`, the buffers of shorts, ints, longs, floats and doubles,
the views of a byte buffer and the members over a buffer (`Charset.encode` / `decode`,
`MessageDigest.update`, `Mac.update`, `Base64`'s `encode` / `decode`, `BitSet.valueOf`) are Java
source answering what a JDK 25 answers -- every text, refusal, order and hash code -- and a
program naming none of them is byte-identical to what it was before them.** Written from the API
documentation and JDK 25's answers to probes
([java-frontend-provenance.md](java-frontend-provenance.md)).

- **One class a kind**, a direct one an array it does not hand out; the five buffers of numbers
  are one template (`gen/buffers.py` of the artefacts). **A view is a subclass** made only where
  asked, so the dispatchers hold none ("The library"); the char view overrides what `CharBuffer`
  writes inline, as `CharBuffer` gaining `set` and `made` cost a program with a `CharBuffer` and
  no view 263 B of wasm.
- **Read off the answers** (`Bytes.java`, `Typed.java`, `Views.java`): the refusals' words
  (`capacity < 0: (-1 < 0)`) and order; `hashCode` over elements cast to `int` (a float's
  saturating); `compareTo` and `mismatch` taking -0.0 for 0.0 and NaN for NaN; the order of
  `order(null)` (little-endian), of a byte buffer's slice, duplicate and read-only copy
  (big-endian), of `compact` (kept), of a view and its slices (the one it was made in); each
  kind's text (`U` where the order is the native one).
- **The native order is little-endian on every target**: a wasm memory is by its specification,
  and `BuiltinSystems` announces `:little-endian` everywhere; a JDK on a big-endian host answers
  otherwise (`deviations.md`, "Buffers").
- **`Charset.encode` / `decode` are `getBytes` / `new String`** (`Codec.java`: identical over
  six charsets, malformed and unmappable input too, but the documented lone surrogate in UTF-8),
  their capacity a JDK's coder's: the length times the average in `float`, then `2n + 1` until
  it fits (every length up to 40, and 1,572,869 characters, where the float and the double
  product differ).
- **The members over a buffer** (`Over.java`) leave it at its limit, but `BitSet.valueOf`, which
  reads little-endian whatever the order; `Mac.update(null)` is `Buffer must not be null`;
  `Base64.Decoder.decode(ByteBuffer)` leaves a refused buffer where it was,
  `incorrect ending byte at N` indexing the array where there is one, else from the position (a
  copy decoded, so `decode(byte[])` is byte-identical; in place cost 66 B).
- **Not made**: the channels (refused by name); the HTTP client's `BodySubscriber` /
  `BodyPublisher` of buffers `.todo/k95`.
- Pins: java-spec `the-buffers-are-arrays-read-in-an-order` (with the `CharBuffer` lines), lines
  in `a-digest-is-the-standards-function-of-its-message`,
  `a-mac-is-an-hmac-of-a-digest-under-a-secret-key`,
  `base64-is-the-rfcs-alphabets-and-a-jdks-refusals` and the `bits` of
  `a-deque-an-optional-the-utilities-and-the-older-classes`.

## The charset coders (2026-11-24, l63)

**Invariant: `CharsetEncoder`, `CharsetDecoder`, `CoderResult`, `CodingErrorAction`,
`CoderMalfunctionError`, `Charset.newEncoder` / `newDecoder` / `canEncode` and
`ByteBuffer.alignmentOffset` / `alignedSlice` answer what a JDK 25 answers -- every step's
result and where it leaves both buffers, every text and refusal -- for the six charsets and for
a coder of the program's own.** Written from the API documentation and JDK 25's answers to
probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **The specified machine around `encodeLoop` / `decodeLoop`**, one package-private class a
  direction for the six charsets (`StandardCharsetEncoder`), so `Utf16` gains nothing; a
  read-only output buffer is a `CoderMalfunctionError`, as a JDK's.
- **Read off the answers** (`Enc`, `Dec`, `Order`, `Result`): the words of each refusal and
  illegal change of state (`Current state = .., new state = ..`,
  `Non-positive averageBytesPerChar`); the averages and maxima (UTF-8 1.1 / 3.0, UTF-16 2.0 /
  4.0, the decoders 1.0 or 0.5 / 1.0); the replacements (`?`, UTF-16 `FF FD`, UTF-16LE `FD FF`);
  a high surrogate ending the input waiting; UTF-16's mark, written at the first step with input
  before looking at it, read in the first two bytes only; which comes first, the room or the
  input; the states each member answers in; `CoderResult` of a length one object; the hooks.
- **The library's encoders take their replacement unchecked**: `replaceWith` asks
  `isLegalReplacement`, which makes a decoder, so a program with an encoder and no `replaceWith`
  holds no decoder; a program's own encoder is checked.
- **Alignment**: a heap buffer refuses a unit above one byte
  (`Unit size unsupported for non-direct buffers: n`), as a JDK 25's. A direct one, an array
  here, answers as if at an address every unit divides; a JDK's starts at a multiple of 16 on
  the Linux host measured (glibc: 16 or 32 over 3,000 allocations), its answer for a unit above
  16 where the allocation fell (`deviations.md`, "Buffers").
- **Measured** (wasm): a program naming no coder is the same bytes, one making a
  `CharacterCodingException` (a strict file read) -34 to +2 B (that class defined earlier):
  `CharsetEncoder.encode` names it in its `throws`, so it enters the table with `Charset`'s
  members ("java.time: the regions"): the API's signature, no way around it. A private member
  answering the `IllegalStateException` of a change of state moved `RuntimeException` likewise
  in EVERY program: it throws instead and returns nothing.
- Pins: java-spec `the-buffers-are-arrays-read-in-an-order` (three encoders step by step, a
  decoder across a split sequence, a coder of the program's own, an aligned slice).

## References and the weak map (2026-11-23, k89)

**Invariant: `java.lang.ref` (`Reference`, `WeakReference`, `SoftReference`, `PhantomReference`,
`ReferenceQueue`, `Cleaner`) and `WeakHashMap` are Java source, and the collector of no target
clears a reference: the program's `clear()` and `enqueue()` do, `enqueue()` alone puts one on
its queue, a weak map keeps a key until it is removed, a cleaner's action runs where `clean()`
is called. Every other answer -- texts, refusals, the queue's order, the map's walks -- is a JDK
25's.** Written from the API documentation and JDK 25's answers to probes
([java-frontend-provenance.md](java-frontend-provenance.md)). The Clojure front end's
portable map keeps every key too ([clojure-frontend.md](clojure-frontend.md)).

- **Why no clearing, measured** (`Collect.java`, 2026-11-23): the specification lets a collector
  keep what it could reclaim, and a JDK under Epsilon (reclaiming nothing) answers every line as
  the four backends do (`Collect.epsilon.txt`); under the default one the difference shows only
  after `System.gc()` (a weak map of 101 keys at 1). A cache of 200,000 dropped keys stays whole
  under both with the default heap; at `-Xmx64m` the default collector clears some, and Epsilon,
  like the compiled class (run with `-Xmx128m`; the `.wasm` takes 151 MB), ends in
  `OutOfMemoryError`. wasm GC has no weak reference: clearing on the JVM and the interpreter
  alone would make the targets differ where a program can tell. `System.gc()` does nothing.
- **Read off the answers** (`Refs.java`, `Queue.java`, `Cleaners.java`): `enqueue()` with no
  queue clears and answers `false`; a reference goes on its queue once, a cleared one too;
  `clone()` throws `CloneNotSupportedException` with no message; a queue answers the last put
  first; `remove` with one there leaves the interrupt set; `Negative timeout value`; a cleaner
  checks `obj` before `action`, runs an action that threw no more and refuses a factory's `null`
  (`NullPointerException`) and started thread (`IllegalThreadStateException`), its thread
  waiting in `TIMED_WAITING`. `create()` starts none: no program can name that thread, and a
  thread on wasm makes functions resumable. A cleanable is a class of its own, a JDK's a
  `PhantomReference` (`deviations.md` says nothing of it).
- **The weak map's order** is a JDK's (`Weak.java`: a model over 600 seeded runs of every
  change, none walking otherwise): the buckets, the chains, the doubling, the walks and the
  lengths, `putAll` giving the length it grew to back where the map holds less than half of what
  it may (a source claiming 40 mappings and walking none reorders a map of five keys). The null
  key's bucket is the last, walked first; a JDK's is an identity hash code's, changing from run
  to run (`deviations.md`).
- **Differ from a JDK** (`deviations.md`): the words of a `NullPointerException`; `trySplit`
  answers `null`, as every spliterator of the subset's does.
- **Measured** (wasm): a program naming none of the classes is byte-identical; `notifyAll` in
  the queue's put is 60 KB of a thread's runtime, paid only where the program calls `enqueue()`,
  as a cache of weak keys never does.
- Pins: java-spec `a-reference-keeps-its-object-until-the-program-clears-it` (its imports are
  the corpus's), and the sealed `Reference` refused as javac does
  (`class is not allowed to extend sealed class: Reference ...`).

## The sublist and the walks of a list (2026-11-20, k62)

**Invariant: `AbstractList` keeps `protected int modCount`; `ArrayList`, `LinkedList` and
`Vector` count their changes of size in it; a walk of an `AbstractList` and a `SubList` of one
refuse with `ConcurrentModificationException` once the count moved behind them, where a JDK's
do.** Written from the API documentation and JDK 25's answers to probes
([java-frontend-provenance.md](java-frontend-provenance.md)).

- **A walk** (`IndexIterator`) re-reads the count after its own change, `set` too (a program's
  `set` may count); a removal leaving the cursor past the end is a refusal at `next`, not a
  silent stop. A part's walk ends at its own length (`walkEnd()`, overridden;
  `list instanceof SubList` in each walk was 800 B), which a change behind it does not move:
  past a removal at the tail `hasNext` is `false`, on a stale part `true`, then `next()`
  refuses. Not made: a part's own iterator as the walk both ways (+2,596 B of wasm in `SzOwn`).
- **A part** (`SubList`) checks the root's count after the index
  (`Index 9 out of bounds for length 2`, `Index: 9, Size: 2`), before it for `listIterator`;
  `subList` and `addAll(int, ..)` check the range against its own length first (`toIndex = 9`,
  `fromIndex(2) > toIndex(1)`): `subList` of a stale part MAKES a part that refuses when used;
  `addAll(i, nothing)` answers `false` unchecked; its spliterator knows its size from when it
  was made. A change through it is seen by a part of a part, its parent and its own walks, not
  by a sibling; a program's list that never counts is never refused.
- **`ArrayList`'s count** moves on `addAll` of nothing, `trimToSize`, `ensureCapacity` past the
  array, `replaceAll` and `sort` (each refusing a change its function makes), not on `set`,
  `removeIf` and `removeAll` of nothing. `ReversedList.subList` is the list's part of the other
  range, reversed, not `RandomAccess`; `LinkedList.Reversed.subList` the view's
  (`Range [2, 1) out of bounds for length 5`).
- **`removeRange` and `clear`**: `AbstractList.clear()` is `removeRange(0, size())`, which is
  `remove(from)` repeated, from the FRONT; `part.clear()` calls the ROOT's `removeRange` once,
  so a program's class overriding it sees every clear; `ArrayList.removeRange` is one change of
  `modCount` (an empty range too; `From Index: 3 > To Index: 1` reversed), not called by
  `ArrayList.clear()`. A range past the end of a program's list ends in its own refusal, a JDK's
  in a `NoSuchElementException` caused by it. Rejected: removing through `listIterator(from)` as
  the specification describes it (+9.7 and +7.7 KB: a virtual call bringing every walk of the
  program and the library).
- **`CopyOnWriteArrayList`'s part** (`COWSubList`, the name and `RandomAccess` a JDK's) checks
  the list's array against the one it last saw (`!=`) before every member but the iterator's, so
  a part of a part, a sibling and the parent refuse once one of them changes the list (a JDK's
  parts are flat); a change that changes nothing (`set` of the element there among them) leaves
  them fresh, `sort`, `replaceAll` and a part's `clear()` never. Its walks are over a copy
  (`for (x : part) part.add(0, x)` runs twice). The index comes first for `get`, `set`,
  `remove`, `addAll(i, ..)`, the change for `add(i, ..)`, `subList`, `listIterator`,
  `addAll(i, nothing)`. Rejected: a `lo` and a `hi` in the list's walk (+499 B in every program
  holding the list).
- **Left** (`deviations.md`): the part of a `LinkedList` or a program's list, which a JDK builds
  from the ROOT's iterator (a stale part's `subList` works, `next()` past the end is a
  `NoSuchElementException`, `remove()` before `next()` a `ConcurrentModificationException`),
  and, by the same cause, the class name: a JDK has two part classes, here one,
  `java.util.AbstractList$SubList`. A second class would cost every program holding a part, for
  `getClass()` and a stale part's answers, which a JDK's program does not rely on. A
  `CopyOnWriteArrayList` part's `trySplit` answers `null`, as no spliterator of the subset
  splits.
- Pins: java-spec `a-collection-is-java-source-and-a-program-s-class-can-be-one` (the walks,
  parts and counts, `removeRange` through `clear()`),
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (`ArrayList`'s struct
  holds no `changes`).

**An index outside a `Vector` (2026-11-25, probes `Vec.java`, `Vec2.java`; JDK 25).** Each member
throws `ArrayIndexOutOfBoundsException` in its own words, checked in `Vector` before `ArrayList`'s
(an `ArrayList` refusal is `IndexOutOfBoundsException`): `elementAt`/`setElementAt`
`3 >= 3`; `get`/`set`/`remove` `Array index out of range: 3`; `removeElementAt` below zero
`Array index out of range: -1`; `add(int, ..)`/`insertElementAt` `4 > 3`; `addAll(int, ..)` any
bad index `Array index out of range: i`; `listIterator` unchanged (`Index: 4`).
- **A JDK words a negative index by its array's capacity** (`get(-1)`: `Index -1 out of bounds
  for length 10` of a default `Vector`, 3 of one made from a 3-element collection; `add(-1, ..)`:
  `arraycopy: source index -1 out of bounds for object array[N]`, N the capacity after the growth
  the add makes). The subset keeps none (`Vector extends ArrayList`, whose array is private and
  grows by half where a `Vector` doubles), so these words carry the SIZE: exact for a `Vector`
  whose capacity is its size, otherwise a deviation (`doc/en/java/deviations.md`). Keeping a
  capacity means shadowing every growth (`add`, `addAll`, `ensureCapacity`, `trimToSize`,
  `Vector(int)`); not made, no consumer.
- **Sizes** (2026-11-25, bytes, wasm P1 / component / JVM class): a program using eight members
  of `Vector` 137,258 / 141,072 / 166,693 before, 139,273 / 143,309 / 171,501 after; a program
  naming no `Vector` byte-identical (130,114 / 133,842 / 159,137).
- Pins: java-spec `a-deque-an-optional-the-utilities-and-the-older-classes` (the lines of
  `elementAt` through `addAll`, the negative index of `add`/`insertElementAt` left out).

## java.util.Formatter and the conversions a format lacked (2026-11-21, k85)

**Invariant: every conversion of a format is written and refused as a JDK 25 writes and refuses
it -- `%java-format` writes `%s %d %x %o %f %e %c %b %n %%`, one Java method of the library
(`java.util.Conversions.more`) the rest -- and a program holds a branch of that method only
where a control of its own can name the conversion and it makes what the branch reads.** Written
from the API documentation; what it leaves open is a JDK's answers to probes.

- **The shape**: a `Formatter` of an `OutputStream` writes through a `BufferedWriter`, as a
  JDK's; an index's digits are no flags (`%10$5d`).
- **The questions** (`JavaIntrinsics.FORMAT_CONVERSIONS`) are answered while the method is
  lowered: the conversions the controls name (a computed one names all: ints and strings
  159,987 -> 177,181 B of wasm), the host's zone (some 62 KB) only where a format can be given a
  `long` or a `Date`. `printf` and `format` of `PrintStream`, `PrintWriter` and `Formatter` name
  their control where called (`JavaIntrinsics.FORMAT_CONTROLS`), so the library's computed
  controls name nothing (a program's own `PrintStream` -5,812 B).
- **A refusal is the run time's raise** (`Conversions.refuse` is `%java-raise`): with
  `throw new` a program formatting `%h` was 195,388 B, with raises 168,278 (a thrown object
  brings the condition report and its printer).
- **What a JDK answered**: `%g` rounds the shortest digits half up, a `BigDecimal`'s zero `%f`'s
  at scale 0 and `%e`'s at any other (`0.00` is `0.00000e-02`); `%a` rounds a precision of 1 to
  12 half to even, a subnormal after its first bit (`0x1.8p-1073`), and counts a width without
  the zeros a precision adds (`%020.3a` of 1.0 is 22 characters); `%t` reads a `long` and a
  `Date` as `Date` does (1582 has 355 days), divides `%ts` toward zero, refuses a
  `TemporalAccessor` lacking a field under the letter asking
  (`IllegalFormatConversionException`; `R` and `T` ask `H`), writes the year of the era for `Y`,
  `C`, `y` (`-12345` is `12346`), ISO's for `F` (`+12345`, `-0005`), and `%tZ` as an offset's id
  or a formatter's `z` in `Locale.US` (all 1,812 zones and instants asked:
  `probes/ZoneNames.java`). The refusals' order (of `%g` and `%a` Clojure's table) and words:
  `Conversion = 'tq'` for `%Tq` too; `MissingFormatWidthException` writes the flags in order,
  then the index (`%-1$g`), `T`'s letter in capitals (`%-TC`). A null array of arguments is a
  null for each specifier, a null under a precision cut (`%.1g` is `n`); `%E`, `%X` and `%C` of
  null and `%E` of an infinity are in capitals; a mismatch names the conversion in lower case
  (`e != java.lang.String`); a `Formattable` writes into a new `Formatter` over the same output,
  of the format's locale.
- **The grid** (`probes/Grid.java`, 9,885 lines): a JDK's lines on the four backends under
  `TZ=UTC` but the 512 refusals of `%s`; `Appended.java` and `Pieces.java` a JDK's but the
  padding (one append of the padded text, a JDK's a character at a time) and the refusal before
  appending.
- **Not there** (`.todo/k95`): the refusals of a flag or precision the run time's conversions do
  not take, every refusal made before a `Formatter` appends anything, the relative index `%<s`.
  A `Formatter` of a file: "Files" below.
- **A format's locale**: `%t` writes the words of the locale any member is given, as a JDK 25
  does: the root
  (an unknown language too) `%tB` and `%tA` short (`Sep`, `Thu`), British `%tb`/`%th` `Sept`,
  Canadian `%tp` `a.m.`/`p.m.` (so `%tr` `P.M.`). **A zone's short name in the root is not
  always its offset**: `%tZ` is a formatter's `z` in the same locale on all 1,812 zones and
  instants in each of `ROOT`, `ENGLISH`, `UK`, `CANADA` (`Zones.java`), the root writing
  `GMT+hh:mm` on 1,492 only (`UTC`, `GMT`, `EDT`, `MSK`, a zone's standard offset in 1950 ...);
  a `long`'s and a `Date`'s host zone stays its offset. A JDK's `Singapore` in `CANADA` depends
  on what the run asked before (`MYT` or `GMT+08:00`); ours is `MYT`. The locale is handed
  (`Conversions.moreIn`) only where the controls can name `%t` or the program makes a
  `Formattable`, and it is no read of `Locale.US` or `Locale.ENGLISH` (`readsEnglish`): the k85
  size programs, `examples/java/shop`, `http/Server.java` and `hello-servlet` are the same
  bytes; a `Formatter` of no locale writing `%t` +2,692 B of wasm (its locale is not known where
  it is lowered).
- Pins: java-spec `a-formatter-writes-to-an-appendable-and-a-format-has-every-conversion`
  (standalone: the refusals as counts and a hash per conversion, each locale's words);
  `a-locale-of-any-language` (an unknown language's `%t`);
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the branches a
  `BigInteger` and a `LocalDate` bring, the `NIL` of a program needing none).

## java.math (2026-11-20, k31)

`BigInteger`, `BigDecimal`, `MathContext` and `RoundingMode` are Java source written from the
API documentation; what it leaves open is a JDK 25's answers to a grid
(94,160 lines), which the
four backends print, all of it.

- **A `BigInteger` is the host's integer held in a field**: against a tagged value (a cons
  `(n . :big)`) a sum of small numbers costs 1.4 times on wasm, less as they grow, nothing on
  the JVM. Source was kept: an intrinsic's `toString`, `equals`, `hashCode` and class name are
  arms of run-time functions every program holds, a program's class could not extend it, and
  `BigDecimal` is source either way.
- **A `BigDecimal` is a `BigInteger`, a scale and the count of its digits once asked**: the
  specification's exact result at its preferred scale, then ONE rounding of an integer quotient
  (`pow(n, mc)`: the documentation's algorithm). In `add(x, mc)` **a number wholly below both
  the other's last digit and the rounding place is replaced by one unit just below them** (it
  moves the sum off the other number and across no boundary of any mode), and `compareTo`, the
  exact conversions, `longValue` and `doubleValue` decide by the place of the first digit
  before any power of ten is made: `1E+1000000000 + 1` at seven digits is seven digits of work,
  `1e1000000000` compares, overflows and is `Infinity` at once. `doubleValue` / `floatValue`
  round once (`%java-nearest-binary` of the ratio).
- **Read off the grid**: the hash codes; a bad digit refused with its group of digits (`For
  input string: "4567890x1"`, ` under radix 16` after it where the radix is not ten); an exact
  `divide(x, mc)` loses its trailing zeros as far as the preferred scale, a rounded one keeps
  them, `sqrt(mc)` loses them as far as `scale / 2` even where rounded (`0.5` at two digits,
  `DOWN`, is `0.7`); a division by zero at a scale is `/ by zero` where the scale moves a
  number at most 18 places and the moved dividend is below 2^63, else `BigInteger divide by
  zero` (224 probe lines); `Division impossible`, `Exponent overflow.`; a text stopping where a
  character must follow has NO message where it ends its array (every string: `"1e"`), `No
  digits found.` / `No exponent digits.` where a range ends inside a `char[]`.
- **`MessageFormat` asks `rontolisp.internal.Exact`** (`-0` where no digit is left), an
  interface, not two `instanceof`: a test of an interface and a call through it hold only the
  classes a program MADE, so a program with no big number pays 33 B; one naming neither class
  is byte-identical.
- **An enum may declare an overload of `valueOf` or `values`** (`RoundingMode.valueOf(int)`):
  `JavaClasses.enumMethod` looks for the signature; the name alone would hide the enum's own
  (`valueOf("X")` then `no suitable method found`).
- **YAVI**: `shelf/Price.java` beside the grid (`UntypedInput.convertNumber`, `decimal` and
  `typeName` lowered as written) prints a JDK's 19 lines on the four backends.
- **`String.format` writes the two by their own digits** (`java.util.Conversions.more`), only
  in a program that MADE a class under `Exact` (asked by name, so that no file is read:
  "java.util.Formatter and the conversions a format lacked"); making a `BigInteger` costs a
  formatting program 8,111 B of wasm whatever it formats, the arguments being an `Object[]`.
  Read off a JDK 25 (`Fmt.java` of the k26 artefacts, 1,006 lines): `%x` / `%o` write the sign
  ahead of the digits (`-0xff`, `(ff)`); `%f` / `%e` round half up; a zero's exponent is its
  scale's place (`0.00` is `0.000000e-02`); a width's zeros stand after the prefix and inside a
  negative number's parenthesis (`%(010d`).
- Not there (`.todo/k90`): the primes (`isProbablePrime`, `nextProbablePrime`, `probablePrime`,
  the constructors of random bits), refused by name.
- Pins: the `standalone:` case `big-integers-and-decimals-compute-round-and-print-exactly` (a
  class under `Number` changes how the corpus program lowers `Number`); the parentheses line of
  `format-renders-the-conversions-programs-write`;
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (a format's third
  function `NIL` with no such number); `valueOf(int)` of an enum in
  `an-enum-is-a-class-whose-constants-are-made-once`.

## java.time (2026-11-20, k35)

The date and time classes, zones, clocks, fields and units (`doc/en/java/classes.md`) are Java
source written from the API documentation; what it leaves open, each answer and each refusal,
is a JDK 25's answers to a grid
(27,197
lines), which the four backends print, all of it.

- **The calendar is arithmetic over the day counted from 0000-01-01** (`Iso`), our own
  formulation, no port: 800 seeded days agree with a JDK over the whole range.
- **A text is read first and its fields checked after** (`IsoText`, one scanner for the eight
  ISO forms), which gives a JDK's three refusals (`could not be parsed at index n`, `unparsed
  text found at index n`, `could not be parsed: <why>` with the `DateTimeException` as cause).
  Read off the grid: fields are refused year, month, day, date, minute, hour, second, then the
  offset's range, but an offset's hours of 24 to 59 at once (`Value out of range: Hour[0-23],
  Minute[0-59], Second[0-59]`), before a wrong month and unparsed text; `Instant.parse` reads
  `24:00:00` as the next day and a second 60 after `23:59` as 59, and refuses wrong fields at
  index 0 with no cause; `Year.parse` alone takes any sign and one to nine digits; `10:15:3` is
  `10:15` and unparsed text at 5; in `10:15+01:00[+02:00]` the offset places the instant and
  the zone writes it (`11:15+02:00`); a text over 64 characters is cut to 64 and `...`. **Not
  copied**: the order of the fields a JDK lists as making no value (`Unable to obtain Instant
  from TemporalAccessor: {..}`) changes from run to run (a hash map of enum constants); the
  library writes one, the corpus compares the words before the brace.
- **Read off the grid, in no documentation**: the hash codes (a region's its id's;
  `ValueRange` and `ZoneRules` hash to numbers of ours, the clocks to a JDK's: 26 clocks of
  the four kinds); a date's
  `compareTo` is
  the difference of the first differing field; `LocalDate.plusDays` past the last day names the
  year within 59 days of December's start, else the epoch day; `INSTANT_SECONDS`' range is an
  `Instant`'s; `Instant.until` in milli- and microseconds cuts toward zero; `ZoneOffset.of`
  reads by the text's length (`UT` is `non numeric characters found: U0T`).
- **A zone every program knows keeps one offset**: an offset, `UTC` / `GMT` / `UT` (as a JDK
  normalizes them: `UTC+1` is `UTC+01:00`) and the 42 names of the tz database fixed by
  definition (computed, no table). **A region whose offset changes is known to a program that
  holds it** ("java.time: the regions"), to any other unknown (`ZoneRulesException: Unknown
  time-zone ID`), as a name its database lacks is under a JDK. Rejected: every region in every
  program reading a zoned text (some 123 KB plainly encoded, more than a tenth of the YAVI
  schema example: `Zones.java` of the artefacts, asked of JDK 25's `java.time.zone`); the
  host's rules -- wasm has no host to ask, and a zoned text would read differently on the four
  backends.
- **`ZoneId.systemDefault()` is the host's offset NOW** as a `ZoneOffset` (the region `TZ`
  names where the program holds it: "java.time: the regions"): `%local-time-offset` is the one
  question every backend can answer (`.kb/time-environment-builtins.md`; on wasm `TZ` and the
  zone files, else UTC, 2.3 KB for the reader). `Instant.now()` is `%unix-nano-time`; nothing
  else is native.
- **A class object costs with its interfaces**: of the 119,841 B of wasm the YAVI schema
  example grew by, 14 KB are 15 class objects (the eight classes and the seven interfaces above
  them). A program naming none is byte-identical.
- **YAVI**: `shelf/Loan.java` beside the grid (`UntypedInput`'s table and the `Year` arm of
  `convertNumber` lowered as written) prints a JDK's 77 lines on the four backends.
- `Duration.dividedBy` is `.todo/k95`'s.
- Pins: java-spec `dates-times-and-instants-are-read-from-their-texts-and-computed-with`; a
  class the subset lacks (`java.util.Currency`, `java.util.Observer`) in
  `JavaLoweringTest#aLibraryOfTheSourcePathIsLoweredAsFarAsTheProgramReaches` and the library
  of `a-program-uses-a-library-read-as-source-from-its-source-path`.

## java.time: the regions (2026-11-20, k42)

**Invariant: a program holds the regions of the tz database it names, each with the changes of
its offset as a JDK's `java.time.zone` answers them; a program that holds none has nothing of
what a region needs -- the forms it had before.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md).

- **Who pays.** `ZoneId.of`, `getAvailableZoneIds` and the `ZoneRulesProvider` members naming a
  zone are natives whose emitter notes the name (`JavaLowering.namesZone`): a constant region
  adds it, a computed name or the question for the names every region; a session carries what
  was noted. A zoned text a file of the program or of a library of the source path parses
  brings what it names (`JavaLowering.readsZones`), so a YAVI schema asking for a
  `ZonedDateTime` reads `"..[Asia/Tokyo]"` as a JDK does, not as a violation (+80,068 B, a
  computed text bringing every region; YAVI 0.18.1 reaches `ZonedDateTime::parse` in no other
  schema). The host's zone brings nothing.
- **No option of the compile holds regions** (decided k51): the source says it already -- a
  constant `ZoneId.of` per region, one `ZoneId.getAvailableZoneIds()` for all -- the same way
  on the CLI, the Maven plugin, the playground and a session, where an option would be one more
  input to four embedders and the same text would compile to two behaviours.
- **`ZoneId.regional()` is a question answered while lowering** (`JavaIntrinsics.answered`),
  behind which stands everything a region needs, so a program without one lowers the same forms
  (YAVI's `Member.java` the same bytes). **A class enters the table where a member of a class
  already there names it**, so a new API member naming a class moves the order of the forms:
  none can keep the bytes.
- **The table** (`src/main/resources/am/ik/rontolisp/java/zone-rules.txt`, read by `JavaZones`)
  is generated by `JavaZoneRulesTest` from the running JDK's public `java.time.zone` and pinned
  to it, the `UnicodeNormalizationTablesTest` way: a zone's `getTransitions()`, its
  `getTransitionRules()`, and its standard offset, which no list holds, asked every five days
  from 1800 to 2100 and a change found to the second. JDK 25.0.5 answers tz database 2026e: 562
  names beside the 42 computed ones (`SystemV/`'s seven fixed names are in the table), 329 sets
  of rules. A line is the names sharing a set, then its text; `tzdb` names the version, `short`
  the pairs of `ZoneId.SHORT_IDS` (JDK 25 maps `EST` to `America/Panama`, `MST` and `HST` to
  regions too, where JDK 8's documentation had offsets). **A JDK with a newer tz database fails
  the test**, which writes `target/zone-rules.txt`; an older one is skipped, saying so (a JDK
  update moves the tz database inside a feature release, where it does not move Unicode).
  `ZoneRulesProvider` is this table's: `getRules` refuses `UT` and offsets with a JDK's
  `ZoneRulesException`; no `registerProvider`.
- **The encoding** (`JavaZoneRulesTest.text`): numbers in digits of five bits and a sixth
  saying more follow, in `$` to `d` without the backslash; a set is its offsets, the two it
  starts with, its yearly rules, then its changes as runs of years one wall-clock rule gives.
  Measured over all sets: a change as seconds since the one before and an offset 123 KB; runs
  with three offsets each 51 KB; what ships 40,825 B. Not taken: sharing a set's tail (a sixth
  of the changes saved); a dictionary across sets (half the runs' rules are distinct: the
  references cost what it saves); gzip (some 15 KB). **Copies of an earlier set's runs** (k51)
  save 11,492 B of the runs' 35,456, only in a program holding all, while a reader following
  them costs every program holding a region 5,867 B of wasm (measured 2026-11-20; with an
  `int[]` and an exception of its own `placed` also lost 2,181 B of the type-test fold's
  proofs) and `JavaZones.table` would have to resolve a copy whose set is not held. Not taken.
- **A program's table** holds only its regions (`JavaZones.table`): all 46,661 B, `Asia/Tokyo`
  alone 57, `Europe/Paris` 328. **Not taken: a region as arrays the compiler writes** (k51), to
  spare the reader: Paris's 102 changes as three `long[]` literals are 38,732 B of wasm (each
  second past an `i31` a boxed literal), so the reader (13.2 KB, most of it array accesses:
  "Numbers") is the cheaper half from the first region on.
- **The rules in the library** (`ZoneChanges`, under `ZoneRules`, which stays the rules of one
  offset): `java.time` asks them in numbers through natives, so `java.time.zone` has no public
  member a JDK lacks. Read off a JDK, in no documentation: a yearly rule counts only past the
  last listed change of the clock and not at all where none is listed (`ZoneRules.of` with
  rules alone answers its base offset for good); a change is before any instant after its
  second, to the nanosecond; a local date-time from the lesser to the greater of a change's two
  local times is in its gap or overlap, the greater excluded; `isFixedOffset` wants no list and
  equal base offsets, and such rules equal `ZoneRules.of(offset)`.
- **A `ZonedDateTime` in a region** moves and settles as a JDK answers (pinned);
  `LocalDate.atStartOfDay` of a skipped midnight is where the gap ends, not midnight moved by
  its length (Toronto 1919-03-31 is `00:30`).
- **`ZoneId.systemDefault()`** in a program holding a region is the zone the host names where
  the program knows it, else the host's offset now. The name, as JDK 25.0.5 answered on Linux:
  `TZ` less a leading colon, or where it is unset or empty the link `/etc/localtime`'s path
  after `zoneinfo/` (`%read-link`: a `.wasm` reads it where `/etc` is granted). A JDK answers
  `GMT+09:00` for `TZ=JST-9` and `GMT` for a name it lacks; here the offset now. Pinned by
  `LocalTimeZoneTest` (`TZ=:<zone>` on the four backends, an `/etc` of the test's on the two
  wasm legs).
- **Five years of yearly rules are kept** (k51): past the last change, `ZoneChanges` keeps the
  changes of the five years about the one the seconds divided by an average Gregorian year
  (31,556,952) give -- the second's year or one beside it (the calendar is never more than 1.2
  days off the average over 400 years), so the window holds the year before and after -- until
  a question leaves it. Against a `LocalDate` and six `Yearly.at` per question (Paris, ns, wasm
  / JVM / JDK): `ZonedDateTime.of(local, zone)` 5,286 -> 2,214 / 1,367 -> 724 / 282;
  `ZoneRules.getOffset(Instant)` 3,572 -> 1,089 / 1,654 -> 437 / 81. Cost: 6.7 KB of wasm in a
  program holding a region; a one-year window bounded by `LocalDate` cost 8.9 KB, an index
  computed per element 9.7.
- **What a program pays** (wasm, `ZonedDateTime.of(.., ZoneId.of(..)).plusHours(1)`, less the
  five years): `"UTC"` as without regions, `"Asia/Tokyo"` +50,261 B. **What cost**: a list of
  `ZoneOffsetTransition`, a `HashMap` or a `Duration` brings each class with its `toString` /
  `equals` / `hashCode` (arrays of numbers do not); a cast brings `ClassCastException` and
  `%class-name`; `System.arraycopy` 6 KB of `replace`; `"..." + offset` of a value the backend
  cannot type is the generic `%string`, whose float arm keeps 17 KB of float printing
  (`offset.getId()` does not).
- **The walk** (every
  change of the 604 zones and the instants and local date-times about it, 362,528 lines): the
  four backends print a JDK's 605 lines.
- **A newer tz database**: under a JDK that has it, `./mvnw test -Dtest=JavaZoneRulesTest`
  fails and writes `target/zone-rules.txt`; copy it over the shipped file, run the test again,
  then `-Dtest=JavaSpecE2eTest` (the region case's lines are the JDK's own) and the walk on the
  four backends.
- Pins: `JavaZoneRulesTest` (the shipped table is the JDK's; a program's table holds what it
  names by every route above; a program naming none has no form of `ZoneChanges`; a session
  goes on holding); java-spec `a-program-holds-the-regions-of-the-tz-database-it-names` (a file
  of its own: the corpus's one program holds no region); `LocalTimeZoneTest` (the host's zone).

## java.time: the formatter (2026-11-20, k43)

**Invariant: a formatter writes a value through the parts its pattern or builder names, reads a
text back through them and makes the fields a date and a time in its resolver style, as a JDK
25 answers; a program that makes no formatter has nothing of it, one that reads no ISO form
makes none, and the classes read a value of another class through its fields only in a program
that can hold one.** Provenance: [java-frontend-provenance.md](java-frontend-provenance.md).

- **The shape** (`java.time.format`): `Patterns` makes a pattern's parts by the API
  documentation's table of letters; `Texts` holds the words of the five locales `Locale` has,
  CLDR's as a JDK writes them (the root's `BCE`, the British `Sept` and `am`, the Canadian
  `a.m.`); `Parsed` has the name a JDK's refusals show (`of type java.time.format.Parsed`).
- **The classes' own `parse` stays `IsoText`'s**, the formatter a second reader of the same
  fields: `LocalDate.parse(text)` is 141,206 B of wasm, with `ISO_LOCAL_DATE` 365,475, which
  one engine would put in every program reading a date. The java.time grid's 503 texts read
  through the ISO forms give the JDK's answers, so the two readers agree.
- **Read off the grid**
  (8,394 lines,
  a JDK's lines on the four backends), in no documentation: the digits a number takes beside
  its neighbours (a run's first reads at most 19, one fewer where 19 pass a long; leniently
  nine: "java.time: what the formatter left"), which sign in which style (`EXCEEDS_PAD`; no
  `-0`), the base of a reduced number, which of two equal words wins (the later: `J` is July),
  the no-offset text read first (`x` reads `+0030` as `+00`), a zone's name the longest the
  program has (`Step.region0` notes no name), a second 60 as 59 (`parsedLeapSecond`), a setting
  changed in a failed optional part staying changed; resolving, style by style: a year of an
  era is a year (strictly only beside an era or a year), a date strictly `LocalDate.of`,
  smartly cut to the month's last day, leniently added up, a time checked minute, hour (smartly
  `24:00` is the next day), second, nanosecond, every field left checked against the date or
  the time (`Conflict found: Field .. derived from ..`; a conversion's conflict `while
  resolving` the field), half a day alone its middle (`AM` is `06:00`).
- **Who pays: two questions answered while lowering** (`JavaIntrinsics.answered`).
  `DateTimeFormatter.wanted()` makes an ISO form only where the program reads its static field
  (a parse by a pattern 428,993 B, 446,350 with every form made); `Iso.foreign()` lets `from`
  read another class's value through its fields only where the program can hold one
  (`JavaLowering.foreignTemporals`; a session always): `LocalDate.until` 167,943 B, 202,041
  otherwise. A program making no formatter is unchanged: the same bytes, or the same forms in
  another order. Of a pattern-format program's wasm, 16 KB are the float arm of a generic
  `%string` that `"" + field` of an interface type brings.
- **YAVI**: `CharSequenceConstraint.isoLocalDate()` and `localDate(pattern)` run: the
  artefacts' `yavi/Event.java` prints a JDK's lines on the four backends. Strictly a pattern of
  `yyyy` makes no date (the year of an era stays a field), so such a check takes `2026-02-30`
  as a JDK does.
- Pins: java-spec `a-formatter-writes-and-reads-a-date-by-a-pattern` (standalone: the corpus
  program makes no formatter);
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the two questions).

## java.time: what the formatter left (2026-11-20, k52)

**Invariant: the classes and the pattern letters below write, read and resolve as a JDK 25
answers; a program pays for the calendar only where it can ask a value for it, for resolving a
field that is no `ChronoField` only where it makes one, and for the letters of weeks, quarters
and Julian days only where a pattern of it can name one.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md). The grid (`Grid.java`, 4,561 lines,
a JDK's lines on the four backends) and the sizes were measured.

- **The classes** and a JDK's texts: `MonthDay` (`Unable to obtain MonthDay from
  TemporalAccessor: {MonthOfYear=.., DayOfMonth=..},ISO of type java.time.format.Parsed`, the
  list in one order), `OffsetTime` (an offset past 18 hours: `Unable to obtain OffsetTime ..
  {OffsetSeconds=n},ISO resolved to <time>`), `InstantSource` (clocks in UTC:
  `FixedClock[..,Z]`, `SystemInstantSource`, `SourceClock[source,zone]`), `datesUntil` (the
  count estimated by 146,097 days in 4,800 months, so `MIN.datesUntil(MAX, P1M).count()` runs
  no date).
- **The calendar** (`Chronology`, `IsoChronology`, `Era`, `IsoEra`, `ChronoPeriod`) is paid
  only where the program reaches `TemporalQueries.chronology()` (`JavaLowering.reach`):
  answered unasked in every class, a program writing by a pattern grew 1.3 KB. Read off the
  answers: `ChronoLocalDate.from` of a value of no calendar names `class java.time.LocalTime`;
  `epochSecond` refuses the year, month, day of the month, hour, minute, second, then the date.
  **Not copied**: `IsoChronology.hashCode` is a class's identity under a JDK (it moves with
  `-Xshare:off`), so ours hashes as an object.
- **`IsoFields`, `JulianFields`, `WeekFields`** are written from the definitions (ISO-8601's
  week 1 holds January 4; a locale's week 1 is the first of the period holding `minimalDays` of
  its days), one formula for all (`WeekFields.weekOne`), compared with a JDK over all 49
  `WeekFields` and 382 seeded dates. A refusal names what a JDK reads first (`Unsupported
  field: DayOfWeek`). `WeekFields.of(Locale)`: `UK` Monday and 4, the others Sunday and 1.
  **The premise was wrong** (measured 2026-11-20, JDK 25.0.5): under `C.UTF-8` a JDK's default
  locale is `en`, whose weeks start on Sunday with one day, as under `en_US.UTF-8`; only
  `en_GB` differs.
- **Resolving a field that is no `ChronoField`** (`Parsed.resolveOthers`, only where the
  program makes such a field): each, in the order read, is asked `resolve(map, parsed, style)`,
  a change asking the first again; fifty changes are `One of the parsed fields has an
  incorrectly implemented resolve method`; two dates are `two different dates`, two times
  `different times`; the week fields' rules are read off the grid. **Not copied**: a JDK
  resolves them in the order of a hash map of identity hashes, which changes from run to run
  (user docs).
- **The letters** `Y`, `w`, `W`, `e`, `c` (`Localized(..)` in the text), `Q`, `q` (the words
  `1st quarter`, `Q1`, `1`), `g` and `ISO_WEEK_DATE` are paid only where a pattern can name
  them: `ofPattern` and `appendPattern` are natives whose emitters note the letters of a
  constant pattern outside its quotes, every letter for a computed one
  (`JavaLowering.namesPattern`). A computed pattern pays 429,380 -> 495,505 B, and so does
  YAVI's `isoLocalDate()`, whose `localDate(pattern)` passes its pattern on (`yavi/Event.java`
  946,257 -> 1,020,590); a program naming none of it is the same bytes or 20 fewer.
- **Runs of numbers** (1,760 lines of `Runs`): leniently a number of no fixed width reads one
  to nine digits and those it keeps for the numbers after it; one appended right after a run
  ends it (`Step.Digits.ended`); a fraction of a fixed count and no point is one of a run.
  `uuuuMMddHHmmssSSS` reads `20260307123456789`.
- **Type arguments are checked once the outermost declaration is complete**
  (`JavaClasses.complete`): checked while another was completed, before its supertypes were
  set, they made `LocalDate` "not within bounds"; a program of several files can hit the same.
- Pins: java-spec `weeks-quarters-a-month-day-an-offset-time-and-the-iso-calendar`
  (standalone); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the
  letters, the resolving and the calendar where a program names them);
  `JavaLoweringTest#aProgramOfSeveralFilesIsReadFromItsSourceTree` (the type argument).

## java.time: the names in words, the localized forms and a day's periods (2026-11-20, k57)

**Invariant: a formatter writes and reads a zone's name in words, a localized form and a
period of the day as a JDK 25 does in the five locales `Locale` has, among the zones the
program has; a program pays for the names of the regions it holds, and for each of the
three only where it can make the part that writes it.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md). The grid (`Grid.java`: a JDK's
lines on the four backends) and the sizes were measured.

- **The localized forms** are the patterns a JDK answers to `getLocalizedDateTimePattern` in
  the four kinds of locale (`Texts.kind`: the root, English for `ENGLISH` and `US`, British,
  Canadian), made into parts once per kind (`Step.Styled`). A value with no date shows the
  calendar in its refusal (`Unable to extract ZoneId from temporal 12:00 with chronology ISO`).
- **A zone's names**: `zone-names.txt`, generated by `JavaZoneRulesTest` from a running JDK's
  public `TimeZone`, `ZoneId`, `DateFormatSymbols` and `DateTimeFormatter` -- regenerate it
  there, never by hand; `JavaZoneNames` makes a program's tables of the regions it holds
  (`ZoneNames.written0`, `read0`). UTC's, GMT's and the fixed zones' names are rules the
  generator asserts (`ZoneNames.fixed`).
- **A name a JDK writes as an offset** (`GMT-05:00`) is the zone's offset when the JDK runs,
  not at the instant written. Measured 2026-11-21 (JDK 25.0.5 at 61 clocks, 1970 to 2030): a
  standard name's is `ZoneRules.getStandardOffset(now)` (604 zones, no difference; 213 change
  with the clock); a daylight name's moves with the zone's past daylight time, not `raw +
  getDSTSavings`, by a function not worked out. **So time passing fails `JavaZoneRulesTest`**
  (at 2024-06-15 in 11 regions) until the file is regenerated. A program carries the snapshot
  of the jar's tz database, as it carries its rules (user docs, "A program holds the
  regions"). Not taken: computing the name where the program runs (a clock read and the rules
  in every program writing one, and that function to find, for a change none is pending of in
  2026e).
- **Which name is written** is a JDK's: of an instant, the rules' `isDaylightSavings` but in
  9 zones' spans, which the generator finds asking the JDK at each change and halfway to the
  next; of a local date-time the same with no span, the zone as a whole in a gap or an
  overlap; with no date-time, the zone as a whole. A JDK that has written `ENGLISH`'s names
  writes some `UK` and `CANADA` ones as `ENGLISH`'s (148 zones): the generator asks `ENGLISH`
  last (user docs).
- **Reading a name** (a JDK's answers): the longest of the names and the ids, a name beating
  an id as long. One a JDK reads as a region the program does not hold is read as the
  program's first region writing it (`JavaZoneNames.read`), one it reads as none not at all.
  The ids are looked through once per reading (`ZoneName.longestId`): `ZoneId.region` per
  length cost 850 µs a name on wasm with 40 regions.
- **A day's period** (`B`) resolves as a JDK's, with an hour of the day checked before the
  hour is, leniently not (`Conflict found: Resolved time 24:00 conflicts with
  DayPeriod(12:00-24:00)`: midnight holds 24:00); the last period read wins. `localizedBy`'s
  null refusal is a JDK's helpful message.
- **Who pays.** `z`, `v` and `B` make their parts only where a pattern can name them
  (`Patterns.zones()` and its kind), a localized form's time notes `z` unless its style is
  short, medium or null. Rejected, measured: a `HashMap` of the names (every class's `hashCode` and
  `equals`, 49 KB), array literals of the patterns and periods (16 KB of top level),
  `Long.parseLong` (3.7 KB).
- Pins: java-spec `localized-forms-a-zones-name-in-words-and-a-days-period` (standalone);
  `JavaZoneRulesTest` (the shipped names are the JDK's; a program's tables hold what it
  holds); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the
  letters, a localized form's time).

## java.time: what k57 left (2026-11-21, k70)

**Invariant: a formatter writes and reads numbers in the characters of its `DecimalStyle`,
reads from a `ParsePosition` resolved or not, serves as a `java.text.Format` and cuts the
fields it read to the ones it resolves; `IsoChronology` and a program's `AbstractChronology`
make a date of a map of fields -- all as a JDK 25 answers; a program pays for each only where
it reaches the member that makes it.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md). The grid (`Grid.java`: a JDK's
lines on the four backends) and the sizes were measured.

- **A decimal style** (a JDK's answers): numbers and fractions are written and read in it,
  offsets, instants, zones and literals written in ASCII, an instant read in it.
- **A reading from a position** (`parseUnresolved`, `parse(text, ParsePosition)`) and **a
  formatter as a `java.text.Format`** (`toFormat`, `DateTimeFormatter$ClassicFormat`): what
  each throws, wraps or notes in a position (`Index 30 out of bounds for length 19`,
  `Format.parseObject(String) failed`) and the two positions' hashes are a JDK's.
- **The fields resolved** (`withResolverFields`) are an array: a `HashSet` put every class's
  `hashCode` in the program (449,446 B of wasm against 415,823).
- **The date fields over a map** (`Chronology.resolveDate`; `AbstractChronology`,
  `IsoChronology`): the documented combinations in order, each field taken out of the map as
  checked, so what is left after a refusal is a JDK's; ISO's refuses an era as `Invalid value
  for era: 2` (the generic `Invalid value for Era (valid values 0 - 1): 2`) and leniently
  leaves a proleptic month unchecked. **`Parsed` keeps its own copy over its arrays**: through
  the map a program reading a date by a pattern was 56 KB larger, so the algorithm is written
  twice and the grid holds both to a JDK.
- **Leniently, a day of the week in an aligned week is the day before the one asked**
  (`Invalid value for DayOfWeek: 0` where `(d - 1 + 6)` truncated modulo seven is none;
  136,518 answers of 136,518). The calendar week's day a day earlier agrees only inside the
  start's week.
- **A localized offset is read as a JDK reads it**: no part range-checked (`GMT+25:00`); in
  full both characters of a pair read before either is tested, so a text ending inside one is
  `StringIndexOutOfBoundsException` at its length (`GMT+1` 5). Leniently `CE` is read in
  English, `BCE` not.
- **Also a JDK's**: the null refusals' helpful words, written out (`Cannot invoke
  "java.time.DayOfWeek.getValue()" because "dayOfWeek" is null`); `Chronology`'s defaults
  (`epochSecond` refusing the offset, the time, then the date).
- **Who pays** (`JavaIntrinsics.REACHES`: whether the program reaches a member, the first
  reach lowering it again): `DateTimeFormatter.decimals()` by `withDecimalStyle`,
  `Parsed.filtered()` by `withResolverFields`, `Parsed.lenients()` by `withResolverStyle`,
  `Patterns.localizedOffsets()` by `O` and `Z` (`GmtOffset`, 3.7 KB).
- Pins: java-spec `a-formatters-decimal-style-a-reading-from-a-position-the-fields-it-resolves-and-a-calendars-map`
  (standalone); `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the
  four questions).

## java.time: the localized pattern of a requested template (2026-11-21, k77)

**Invariant: `ofLocalizedPattern`, `appendLocalized(String)` and
`getLocalizedDateTimePattern(String, ..)` find the pattern a JDK 25 answers for a requested
template in the five locales `Locale` has and refuse what it refuses; a program pays for the
skeletons and their matching only where it calls one of them, and for a week's, a quarter's,
a period's or a zone's parts only where a template can name one.** Provenance:
[java-frontend-provenance.md](java-frontend-provenance.md). The grids (`Grid.java`,
`Every.java`: a JDK's lines on the four backends) and the sizes were measured.

- **The rule, read off a JDK's answers** (JDK 25.0.5: every date and every time template of
  bounded counts, every resolving date before every time and 2.5 million seeded ones, in each
  kind of locale and `en-001`; a model of it gave all of some sixteen million answers), not
  UTS #35's matching: j and C are the locale's hour, `h` beside a `B` (a British template then
  matched among `en_001`'s skeletons, the root's `Bj` having none); the skeleton has the
  template's letters, each as many but the month's and the day of the week's, and is the
  nearest by the month's width, then the day's; **no width is adjusted** (`yMMMMEEEEd` is
  `y MMM d, E` in the root); with none, the date part and the time part are matched alone and
  joined as a localized form joins them, a part with none refused (`Requested template "s"
  cannot be resolved in the locale "en_US"`); no skeleton has `z`.
- **Refusals**: `getLocalizedDateTimePattern` checks the template, the calendar, the locale
  for null, then the order (`Requested template "dM" is invalid.`; with j or C `... which
  translated into "HHdC" after the 'j' or 'C' substitution.`); `ofLocalizedPattern` and
  `appendLocalized` at once (`Requested template is invalid: jjj`). A calendar of no type
  (`getCalendarType` null) is refused where a JDK looks up a locale's patterns, in its words
  (`Cannot invoke "String.equals(Object)" because "calType" is null`; `"requestID"` for a
  localized form's styles).
- **A formatter of a template** (`Step.Templated`; `Localized(yMMMd)` in its text) finds its
  pattern where it writes or reads, once per kind of locale, and refuses there.
- **The data** (`Texts.skeletons`, CLDR's as a JDK answers them, under the Unicode entry of
  `THIRD-PARTY-NOTICES.txt`): five sets, each what differs from the one it adds to. A newer
  JDK: the spec case's JDK leg fails first; `Every.java`'s lines under it against the build's
  say which skeletons moved.
- **Who pays**: `ofLocalizedPattern` and `appendLocalized(String)` are natives
  (`JavaIntrinsics.templateLetters`): a constant template names its `w` (`w` and `Y`), `Q`,
  `B` and `v`, a computed one all five. `Texts.ownCalendars()` keeps the calendar's check and
  `Step.templated()` the locale handed to the parts out of other programs (handed always: 249
  and 362 B on programs writing and reading by a pattern; behind the question 106 and 146).
- **Rejected, measured**: `String.indexOf(String)` and `replace(char, char)` in the lookup
  (6.2 KB of the run time): the text is walked by characters. **Not taken: an index of where
  each set's first letters start**: a time's lookup 14 µs on wasm against 130, but 1,066 B in
  every program of a template, which looks its template up once per kind of locale; nor the
  times first in a set (92 µs, a date's of `y` 166).
- Pins: java-spec `localized-forms-a-zones-name-in-words-and-a-days-period` (the templates);
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (a template's
  letters, the calendar's check where no calendar of the program's own is).

## java.time: a calendar of the program's own (2026-11-21, k78)

**Invariant: a calendar of the program's own has the date-time, the zoned date-time and the
period a JDK 25 gives it, its dates the defaults, and a formatter of it writes and reads in
it, as a JDK answers; a program pays for any of it only where it makes a `Chronology` that is
not ISO's, and for the calendar's zoned date-time where it writes only where it can give a
formatter a zone.** Provenance: [java-frontend-provenance.md](java-frontend-provenance.md).
The probes (`probes/`: the same lines on the four backends; where a JDK's differ, the README)
and the sizes were measured.

- **A JDK's `zonedDateTime` fails** (measured 2026-11-21, JDK 25.0.5) with an internal
  `ClassCastException`, casting the calendar's date-time made from ISO's; `localDateTime` of
  a calendar of `LocalDate`s is ISO's `LocalDateTime`.
- **The calendar's values** are `java.time.chrono`'s package-private `ChronoLocalDateTimeImpl`,
  `ChronoZonedDateTimeImpl` and `ChronoPeriodImpl` (the names a JDK's `getClass()` and casts
  show). Read off the answers, where they differ from ISO's: a time-based unit carries half
  days in 256ths of the amount first (ISO's `LocalDateTime` too), and `until` cuts the days and
  the times each by itself; a zoned date-time moves its local date-time by every unit, keeping
  the offset where it can (ISO's moves the instant), and counts `until` at this offset; a
  period adds months and years together only where the calendar's months of a year are a
  fixed count (else `Chronology mismatch, expected: Shifted, actual: ISO`). A change is
  checked by a cast and the calendars compared (`expected: X, actual: Y` for a date,
  `required: X` for the others; a refused cast in a JDK's words, `(java.time.LocalTime and
  java.time.chrono.ChronoLocalDate are in module java.base of loader 'bootstrap')`).
- **The defaults** of `ChronoLocalDate`, `ChronoLocalDateTime`, `ChronoZonedDateTime` and
  `ChronoPeriod` are the documentation's. ISO's values order one of another calendar the same
  way, `Period` refuses one (`Period requires ISO chronology`), `IsoFields` has no field of
  one.
- **A formatter of a calendar** (`DateTimeFormatter.calendared`): a value with an instant is
  written as the calendar's zoned date-time, one with date fields but no date refused (`Unable
  to apply override chronology 'Thin' because the temporal object being formatted contains
  date fields but does not represent a whole date: 2026-03`); a text's date is the calendar's
  `resolveDate` over a map, one of another calendar refused (`ChronoLocalDate must use the
  effective parsed chronology: Thin`), excess days an ISO period the calendar's date refuses.
- **An era is a number in a calendar of a type** other than `iso8601` or `gregory`: a JDK has
  the words of five other types (`buddhist` `BE`, `japanese` `Meiji` ..), the library not
  (`.todo/k92`).
- **A wildcard given is read as its capture** (`JavaTypes.contains`, JLS 5.1.10): a
  `ChronoLocalDateTime<?>` is a `ChronoLocalDateTime<? extends ChronoLocalDate>` by the
  declared bound, which the library's own `localDateTime` needs.
- **Who pays**: `Iso.ownCalendars()`, `IsoFields.ownCalendars()`, `Texts.ownCalendars()`
  (`JavaIntrinsics.OTHERS`: a `Chronology` made that is not ISO's), `DateTimeFormatter.zones()`
  (`REACHES`, `withZone`). Other programs pay at most 138 B; one reading a date in its own
  calendar pays the map's `resolveDate`, the 56 KB ISO's dates do not. Rejected, measured: a
  static list of the date's fields (1.7 KB in every program with a formatter), a loop over
  `ChronoField.values()` (3 KB of constants), the zoned date-time and the refusals' class
  objects in every program writing in its own calendar (30 KB).
- Not there: the weeks, the Julian days, `appendChronologyId` / `appendChronologyText` and the
  other types' words (`.todo/k92`); an instant read beside a date or a time (`.todo/k93`, in
  ISO's calendar too). The README's other differences: helpful null messages, identity hashes,
  a map's order.
- Pins: java-spec `a-formatters-decimal-style-a-reading-from-a-position-the-fields-it-resolves-and-a-calendars-map`
  (`calendarsOwn`, with ISO's half days past the end);
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (`L17`, `L18`: the
  calendar's writing and reading where a program makes one, its zoned date-time where it can
  give a zone).

## java.time: what a formatter costs (2026-11-21, k79)

**Invariant: a formatter's parts, the lenient resolving and the names of zones in words are
in a program only as far as its patterns, styles and zones can name them, read through the
constants every call of a static or private method passes; what a program prints is a JDK
25's.** Provenance: [java-frontend-provenance.md](java-frontend-provenance.md). The programs
(each way to a parameter: a JDK's lines on the four backends) and the sizes were measured.
Four leads, each measured first (wasm):

- **A constant passed on** (taken): a pattern, template or zone id a native notes (`ofPattern`,
  `ZoneId.of` and their kind) that is a parameter of a static or private method no body
  assigns names what every call passes there, a capturing lambda included
  (`JavaClasses.namingParameters`); the first such parameter, or a later assignment, lowers
  the program again; a reference or a session names what a computed text names. A
  constructor, an overridable instance method and a field are no such parameter. YAVI's
  `isLocalDatePattern` is private: k43's `yavi/Event.java` 921,352 -> 848,308 by this alone.
- **Resolving leniently** (taken): every lenient branch of `Parsed` is behind `lenients()`,
  which `withResolverStyle` answers unless its argument is a read of `STRICT` or `SMART`; a
  question folds through `!`, `&&` and `||` where its first operand decides
  (`JavaLowering.answered`). A date read by a pattern 1.2 KB smaller.
- **The masked operations of the parts** (not taken: the premise does not hold):
  `Step.DayPeriod.parse` is 2,331 B, `ZoneNames.read()` 2,472, `Parsed.hourInPeriod` 904, not
  4 to 6 KB each.
- **The names of every region** (taken): the written table holds each group of six names a
  kind of locale writes once, and a region the numbers of its four (`JavaZoneNames.written`):
  every region 90,405 -> 53,865 characters, one region 517 B more for the lookup. Not taken:
  the root's city out of its group (some 50,500 characters); the names read (26,798) repeat
  little.
- **The parts of letters no pattern names** (taken, the largest): a date written by
  `uuuu-MM-dd` was 39.5 KB over one by the builder's numbers, read 55 KB. Words, a reduced
  year, a fraction, a zone's id, offsets, a pad and an optional section are behind questions
  of the letters noted (`Patterns.words()` and its kind), each `|| localizes()`: a localized
  form's or a template's patterns are read where the program runs, so a program reaching
  `DateTimeFormatterBuilder.parts` keeps every part. A gate not taken throws the subset's
  refusal, which no pattern of the program reaches. Left: a localized form naming what its
  styles hold, `ofLocalizedDate(MEDIUM)` written and read 381,815 B against
  `ofPattern("MMM d, y")`'s 337,012 (`.todo/k94`).
- **What a program pays**: a date written by `uuuu-MM-dd` 281,671 -> 254,076 B, read 358,978
  -> 315,477; `Event.java` -> 799,945; a program of no pattern the same bytes. The floor, a
  date by the builder's numbers: 242,116 written, 303,530 read.
- Pins: java-spec `a-pattern-and-a-zone-a-method-is-given` (standalone: no other case's
  pattern names its letters);
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (`L15` the parts
  and the lenient branches, `STRICT` too; `L16` a localized form keeps them; `L19` a pattern
  through two methods, a reference, an assignment);
  `JavaZoneRulesTest#aProgramsTableHoldsTheRegionsItNames` (a zone through a static method,
  the groups).

## java.util.Date and java.sql (2026-11-20, k37)

`java.util.Date` and `java.sql`'s `Date`, `Time`, `Timestamp` are Java source, written from
the API documentation; what it leaves open is a JDK 25's answers to a grid.

- **`java.sql` is outside `java.base`**: `import module java.sql` brings `java.sql`,
  `javax.sql` and the library's packages of the modules it requires transitively, as on JDK
  25 (`JavaClasses.SQL_MODULE`). Two `Date`s take a compiler's two
  levels of imports: a package imported on demand shadows a module imported (a compact source
  file's implicit `java.base` included); two of one level are `reference to Date is
  ambiguous` where the name is written.
- **The calendar is `rontolisp.internal.Dates`**, our own day count per calendar: Julian
  before 1582-10-15, Gregorian from then on, the year of its era (1 BC is `1`, as a JDK
  writes it). A wall time carries its seconds into the day first and THEN picks the calendar
  (`1582-10-14 24:00:00` is the Gregorian 10-15).
- **The zone is the host's at the instant** (`%local-time-offset`, clamped to the +-8.64e12 s
  every host answers), as a JDK's `Date` reads its zone's rules at the instant -- unlike
  `ZoneId.systemDefault()`, which keeps today's offset; a time the clock skips or shows twice
  takes the offset that is not summer time, as a JDK does in the zones probed. Differences:
  before a region's first rule the host's local mean time (New York `-04:56:02` before 1883,
  a JDK EST); past the year 275,760; the zone's name, `UTC` at offset 0 and `GMT+hh:mm`
  otherwise, what a JDK writes for a zone of no name (a JDK `JST`, `EST`).
- **A text is read as a JDK reads it**: `Timestamp.valueOf` trims and range-checks the date
  before the time, whose fields are any int and carry (`25:61:61`); `java.sql.Date.valueOf`
  and `Time.valueOf` do not trim; a number is refused as `Integer.parseInt` of a range
  refuses it (`Error at index 1 in: "1x"`).
- **`Timestamp` keeps the whole seconds in `Date`'s milliseconds and the rest in `nanos`**;
  `Date`'s `hashCode`, `equals`, `before` and `after` read `getTime`, so
  `date.equals(timestamp)` can be true and the converse not.
- Refused by name: the members deprecated since JDK 1.1, `Date.parse`, `toGMTString`. A
  non-ASCII digit is read on the interpreter and the JVM only, as `Integer.parseInt`'s.
- **YAVI**'s schema example compiles unpatched; it and `shelf/Row.java` beside the grid
  (`java.sql` values read by a schema of the `java.time` classes) print a JDK's lines on the
  four backends.
- Pins: java-spec `a-date-is-milliseconds-and-java-sql-reads-and-writes-wall-times` (its lines
  hold in any zone: a `Date`'s text is printed without the zone's name), the `java.sql.*` line
  of `a-compact-source-file-starts-at-its-instance-main` (the shadowing),
  `JavaLoweringTest#anErrorTheLoweringDependsOnIsReportedAtItsPosition` (the two ambiguities).

## java.sql's interfaces and DriverManager (2026-11-22, l00)

**Invariant: `java.sql`'s and `javax.sql`'s interfaces carry every member of JDK 25's, so a
program's class implements them as it would under a JDK (a driver of its own included), and
each default member answers what a JDK's does; `DriverManager` and the `SQLException` family
are Java source answering what a JDK 25 answers, the lines they write to the log writer
included.** Theme: JDBC for PostgreSQL (l01): what pgjdbc 42.7.14 implements and calls
decided the cut. The interfaces were written by a script from the API documentation's
signatures (`gen/gen.py` in the artefacts), the classes by hand. Provenance:
`java-frontend-provenance.md`, the l00 entry.

- **A `throws` clause is checked once the outermost declaration is whole** (`boundChecks`):
  checked at once, `Throwable`'s `printStackTrace(PrintStream)` made every program's first
  declaration a cycle through `AutoCloseable.close() throws Exception` (`Exception cannot be
  converted to Throwable`).
- **What the JDK answered, kept**: the defaults
  (`getLargeUpdateCount`, `setLargeMaxRows`, `executeLarge*` throw
  `UnsupportedOperationException("<name> not implemented")`, not a delegation to
  `getUpdateCount`; the others
  `SQLFeatureNotSupportedException("<name> not implemented")`); the lines each
  `SQLException` constructor writes to a log writer; `DriverManager` making the services'
  drivers once at the first lookup, a provider that cannot be made ending the making
  unreported, and trying each driver in order (`    trying C`).
- **Not matched**: a trace's frames (none here); `jdbc.drivers` (`System.getProperty` knows
  no such key); the caller's class loader (one here); `getDrivers()`'s enumeration class.
- **Sizes** (2026-11-22, wasm Preview 1 / component / JVM class, bytes): a program naming no
  `java.sql` byte-identical (`Hello`), programs that trace (`examples/java/shop`) within 84 B
  with each class byte-identical; the corpus case 374,461 / 378,719 / 638,675, `--native` 4,453,024.
- **Pinned by** java-spec `a-driver-of-the-program-s-own-answers-rows-through-driver-manager`
  (`standalone:`, a services file of the tree; `--native` checked by hand with the artefacts'
  `legs.sh`), `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles` (every new file compiled
  by javac, the throws clauses against JDK 25's by reflection),
  `#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition` (`TimeZone.getAvailableIDs()`).
- **Not made here**: `SQLData`, `SQLInput`, `SQLOutput`, `SQLPermission` (pgjdbc's
  `PgConnection` holds two in static fields) and `javax.naming` (pgjdbc's `BaseDataSource` is
  `Referenceable`): next section. `Calendar`: "Calendar, GregorianCalendar, TimeZone,
  SimpleTimeZone" below; `java.util.logging`: "java.util.logging" below (l78); `jdbc.drivers`: decided by l20.

## What l00 left of java.sql (2026-11-24, l20)

**Invariant: `SQLData`, `SQLInput`, `SQLOutput`, `SQLPermission` with `java.security`'s
`Permission`, `BasicPermission`, `PermissionCollection` and `Guard`, and `javax.naming` as far
as a data source reaches it (`Referenceable`, `Reference` and its addresses, `NamingException`,
the interfaces a program implements, `javax.naming.spi.ObjectFactory`) answer what JDK 25's
answer; a program naming none of them compiles to what it did.** The interfaces are l00's
script's.
Provenance: the l20 entry of `java-frontend-provenance.md`.

- **What the JDK answered, kept** (probes `SqlDefaults`, `Perm`, `Perm2`, `Naming`,
  `Naming2b`, `Bin`): `SQLInput.readObject(Class)` and `SQLOutput.writeObject(Object,
  SQLType)` throw `SQLFeatureNotSupportedException()` with no message;
  `Permission.checkGuard` throws `SecurityException("checking permissions is not
  supported")` -- JDK 25 has no security manager --, so nothing is checked anywhere;
  `BasicPermission` implies only of its class, `*` or `.*` every LONGER name starting with
  what is before the `*` (`**`, `a*`, `*.` are plain names), its collection in a
  `ConcurrentHashMap`'s order (a JDK's table grows at the twelfth name, a `HashMap` at the
  thirteenth); the addresses' and a `Reference`'s equality, hashes, texts and clones
  (`Reference.equals` reads the OTHER's class name first); `NamingException`'s root cause is
  `getCause`.
- **A trace's causes are what `getCause` answers**, not the private field, so
  `NamingException`'s and `InvocationTargetException`'s are traced as a JDK traces them
  (probe `Vec`; java-spec `a-class-is-a-value`'s `traced`). What nothing caught is reported
  with its causes where a cause constructor, `initCause` or `addSuppressed` is reached, and
  also where a class of the program, or a library class of a reached member, overrides
  `getCause` under `Throwable` (`JavaLowering.answersCause`; "Exceptions" below).
- **Not made**: `NamingException.appendRemainingComponent`, `CompositeName`, `CompoundName`,
  `InitialContext`, the other packages of `java.naming` -- no consumer. `jdbc.drivers` stays
  unread: no target has the key (`System.getProperty` answers the separators and the
  encodings, and a program cannot set one), and a class it names could be found only through
  an index (`Class.forName`, "Annotations read where the program runs" above); the services
  file is the way a driver is found. A `NullPointerException`'s words differ, as everywhere;
  `Reference.get(int)` out of range is `Vector`'s refusal ("An index outside a `Vector`").
- **Sizes** (2026-11-24, wasm Preview 1 / component / JVM class, bytes): without the
  `Throwable` change every program measured is byte-identical (`Hello`, a `getConnection` of
  no driver, the shop, httpbin, mustache); the trace through `getCause` moves every program
  that traces by -41 to -87 B of wasm, mustache +564 and the guestbook +848 (both reach
  `InvocationTargetException`, whose target the trace now writes).
- Pins: java-spec `a-driver-of-the-program-s-own-answers-rows-through-driver-manager` (a
  `Point` of the program through `SQLData`; the defaults; `SQLPermission`'s wildcards, its
  collection past the growth; a `Referenceable` `PoolSource` and its `ObjectFactory`;
  `NamingException` and its trace), `a-class-is-a-value` (`InvocationTargetException`'s
  trace), `an-uncaught-exception-answers-its-cause-through-get-cause`,
  `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles` (every new file through javac, the
  throws clauses against JDK 25's).

## Calendar, GregorianCalendar, TimeZone, SimpleTimeZone (2026-11-25, l79)

**Invariant: the four classes answer what JDK 25 answers -- the rules of the item's `spec.md`,
each with its evidence, and nothing else: what no rule covers is refused with
`UnsupportedOperationException`; a program naming none of them is byte-identical.** Written
under rule 8 of [java-frontend-provenance.md](java-frontend-provenance.md): the spec and its
probes were written by one session, the code by another from them; `rules.md` there maps every
rule to the member answering it.

- **The shape**: `Calendar` keeps the fields, their set order (`order` per field: 0 never, 1
  computed, then 2, 3 .. by the program; reset by each computation) and the documented
  protected fields; `GregorianCalendar` resolves (`computeTime`), computes the fields of an
  instant (`computeFields`), adds and rolls; `CalendarDays` is the day arithmetic of both
  calendars. A zone answers a calendar through three package-private members of `TimeZone`
  (`offsets`, `instant`, `skips`) a program's zone and `SimpleTimeZone` inherit (rule 19);
  `NamedZone` (one offset) and `RegionZone` (a region whose offset changes) override them.
- **When a JDK computes** (m45, rules 68-69): setting the instant computes its fields at once
  (a `null` zone throws there); a set computes nothing, so after `setTimeZone` it joins the
  earlier zone's fields; a new week pair recomputes them at once. A field computation the
  library refuses at `setTimeInMillis` is kept in `Calendar.refused` and raised by the set or
  the computation that needs the fields, so a program reading only the instant (a sentinel
  at the long's end in a region) goes on.
- **A region is java.time's rules read three ways** (`RegionZone`): before
  1900-01-01T00:00Z the raw offset; to 2100 in the raw offset's local time the rules; after,
  the rules where they go on by yearly rules, else the raw offset, Gaza's and Hebron's
  Saturday-the-24th change a week later. The 1900 and 2100 switches are changes like the
  rules': a local time is read by walking back from 18 hours past it to the last change it is
  after (rule 39). The rules are asked in numbers of `ZoneChanges` through natives
  (`RegionZone.wall0` ..): seven new static methods there, none of the old touched, so a
  program holding regions without a calendar keeps its bytes.
- **Who pays**: `TimeZone.getTimeZone` notes its id as `ZoneId.of` does (a three-letter id
  the region it stands for; a computed id every region); `TimeZone.getDefault` -- and with it
  `Calendar.getInstance()`, `new GregorianCalendar()`, the protected `Calendar()` -- notes
  every region through `host0`'s native (`JavaLowering.namesHostZone`, which counts a call in
  the library too: the library's body is lowered only where the program reaches it), since
  the host names its zone where the program runs. Rejected: the host's offsets
  (`%local-time-offset`, as `Date` reads them) for the default zone -- a JDK's default is the
  region with its rules (raw before 1900, the standard/daylight split), which the host's TZif
  does not give. A `RegionZone` is made only where `TimeZone.regional()` answers that the
  program holds a region, so a program of fixed zones carries none of it.
- **Sizes** (2026-11-25, wasm / component / jar, bytes; `sizes/` of the artefacts): byte-
  identical without a calendar: `Hello`, a `Date` and a `Timestamp`, a `ZonedDateTime` in
  Paris, `examples/java/shop`, l01's `Ladder` over jdbc-postgresql. A calendar in a fixed zone
  85,680 / 88,027 / 59,519; in one region 123,217 / 125,651 / 74,227; `Calendar.getInstance()`
  (every region) 183,915 / 188,498 / 118,221 (m45's rules 68-69: about 1.5 KB of wasm more).
- **Traps the JDK showed** (each a rule of phase 2 with its probe): `WEEK_OF_MONTH`'s
  `7 x (n - 1)`, `DAY_OF_WEEK_IN_MONTH`'s `7 x n` and `12 x AM_PM` wrap in 32 bits while the
  other sums are exact; a `SimpleTimeZone`'s year is the instant's, so a southern schedule with
  a start year is in daylight saving the January after the start year begins; a set
  ZONE_OFFSET of a simple zone takes the raw offset's place in the question of daylight saving.
  A JDK's `SimpleTimeZone` answers depend on what that zone (or the zone a clone copies) was
  asked before; probe with a zone never asked per question (`SimpleReach.java`): one zone
  asked in order showed misses days from any new year. A year whose start and end are one
  instant has no daylight saving.
- **Refused** (no rule; `deviations.md`, "Calendars and time zones"): the offsets of a region
  given another raw offset, the long's last three years of a region or a schedule, a schedule
  before 1600 or within |raw| + savings of a new year where a change is as near
  (`SimpleReach.out`: 0 of 334,215 misses outside), the four week rolls about 1582-1583 and
  of another year's week, a time field rolled into a skipped time from after that change, `DAY_OF_WEEK_IN_MONTH` below -306783378, a week pair outside 1 to 7. `toString` is
  `Object`'s.
- **Left**: `ResultSet.getDate(int, Calendar)` of `jdbc-postgresql/` reads
  `calendar.getTimeZone().toZoneId()`, which the subset lacks (`.todo/l23`).
- Pins: java-spec `a-calendar-reads-sets-and-moves-the-fields-of-an-instant-in-a-zone`
  (standalone: the default zone holds every region; its lines hold in any zone, the
  three-letter warning on the standard error); `JavaJdkMembersTest` (the three new classes in
  `jdk-members.txt`); `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles`.

## java.util.logging (2026-11-24, l78)

**Invariant: a program's loggers, levels, handlers, formatters and records answer what JDK
25's do with no configuration, `SimpleFormatter`'s two lines included, and a record knows the
class and the method that called its logger, named as javac names them; a program naming no
`java.util.logging` member compiles to the bytes it did.** Probes (`Lv`, `Lg`, `Lg2`, `Fm`, `Fm2`,
`Fm3`, `Hd`, `Mg`, `Lz*`, `Gp`: every line but those named under "Not matched" a JDK's on the
four backends); provenance: the l78 entry of `java-frontend-provenance.md`.

- **Where a record was made**: a JDK walks its stack; here the call tells it
  (`JavaLowering.toldCaller`, `Logger.calledFrom`). A super call from a subclass of `Logger`
  tells the subclass's method, as a JDK; a method reference tells `nil` (a JDK names the
  frame calling the functional interface).
- **The method's name**: **javac 25 names a lambda's method `lambda$<member>$<n>`, `<member>`
  the method's name, `new` for a constructor, an instance initializer and an instance
  field's initializer, `static` for a static one; `n` counted from 0 per class body and
  member name, overloads sharing one count, in the order the lambdas START in the source (an
  outer one before its inner ones)** (`javap -p`, probes `P2`-`P6`). A lambda is named by its
  position (`CompilationUnit.lambdaNames`): the parser undoes and redoes a reading.
- **What the JDK answered, kept**, though it looks wrong: `Level.parse` of an unknown number
  makes a level; a logger made re-parents over a `setParent`; `isLoggable(OFF)` is true
  unless the level is `OFF`; `Logger.global` before any `getLogger` writes nothing;
  `formatMessage` leaves `{٤}`; a `StreamHandler` writes nothing before a flush;
  `SimpleFormatter`'s time is proleptic at the host's offset (eleven instants, years 1 to
  9999, under UTC and New York). `getProperty` lacks a JDK file's `FileHandler` keys.
- **Not matched**: the frames (none here); a record's thread is `threadId()`, rontolisp's own
  (main 1, a JDK's 3); a method reference's source; a lambda after a reference javac makes a
  method of for types; a `MessageFormat` type (`{0,number}`) throws
  `UnsupportedOperationException` (the subset's `MessageFormat`), which a handler reports; a
  `NullPointerException`'s words.
- **Decided, not made**: a configuration file (`java.util.logging.config.file` /
  `config.class`: no target has a system property a program can set, l20's reading of
  `jdbc.drivers`) and `readConfiguration(InputStream)`: a program sets levels, handlers and
  formatters itself, as under a JDK; `SimpleFormatter`'s format property (a computed control
  would make every logging program carry every conversion of `Formatter`); resource bundles
  (a subclass's bundle name refused at run time); `FileHandler`, `SocketHandler`,
  `MemoryHandler`, `XMLFormatter`, which no consumer asks for (pgjdbc, JUnit, SLF4J's and
  Commons Logging's JUL bindings).
- **Sizes** (2026-11-24; `size/` of the artefacts): byte-identical on wasm, the component and
  the JVM where no logging member is named (`NoDriver`, whose `Driver.getParentLogger` names
  `Logger`; the eight examples). Chosen on `Logger.getLogger("app").info("started")`
  (Preview 1): the tree's children a `ConcurrentHashMap` too, no `HashMap` beside it (383,699
  -> 370,593 B); a message's parameters a `LogRecord.Parameters` made only by
  `setParameters`, so `MessageFormat` is only where some can be given (-> 338,386).
- **Pinned by** java-spec `a-program-logs-through-java-util-logging` (`standalone:`;
  `SimpleFormatter`'s time checked against `%tb %td, %tY ..` of the record's instant),
  `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles`.

### The host's log, and SLF4J and Commons Logging through it (2026-11-25, m14, m17)

**Invariant: the build decides where a program's records go, the program does not change:
the standard error by default; under `--wasi-logging` the root logger's default handler,
`java.util.logging.HostHandler`, hands each record to `wasi:logging/logging` (`0.1.0-draft`),
a component's import or a `--no-wasi` module's through the canonical ABI, which the glue
answers on the level's console method. SLF4J 2 through `slf4j-jdk14` and Commons Logging
compile from their published sources jars and run through it, each record naming the frame
that called the facade, as its binding does under a JDK. A build without the flag compiles to the bytes it did.**
Provenance: the m14 entry of `java-frontend-provenance.md`.

- **Measured first** (2026-11-25): wasmtime 51.0.0 (`-S help`) and jco 1.42.0's shims offer no
  `wasi:logging`; wasmCloud's documentation (v1) lists `wasi-logging 0.1.0-draft`, Spin v3's
  API guide does not. So the import is opt-in, the console the default.
- **Opt-in by a flag, not by the program's handler** (decided): that handler would be a class
  no JDK has, so one source could not run on every target (a Preview 1 module refuses the
  `enum` at its WIT line), and SLF4J's and a library's records go where the root's go.
  `--wasi-logging` (the plugin's `wasiLogging`, the feature `rontolisp-wasi-logging`) takes
  `--component` or `--no-wasi`; the rest refuse it by name (no WIT interface on a WASI
  command module's host or `--native`'s runner, the standard error on the interpreter and the
  JVM, no enum under `--no-gc`).
- **`HostHandler`** is package-private, as no JDK has it, and passes INFO and above, as the
  console: the flag changes where the lines go, not which (levels:
  `doc/en/java/logging.md`). Its native `%host-log` is a WIT import
  (`.kb/wasm-import.md`, "A WIT interface beyond the flat set", the Worker's glue too);
  `logging.wit` holds the interface's names and types, no text of the proposal.
- **SLF4J 2.0.20 through `slf4j-jdk14`**: its census's refusals (`java.lang.SecurityManager`,
  `ClassLoader.loadClass`) are written from the API and JDK 25's answers (`Setting a
  Security Manager is not supported`; `getClassContext` answers no class, five under a JDK).
  Its binding finds the caller on a JDK's stack; here a call of `org.slf4j.Logger`'s methods
  tells it (`JavaLowering.tellsCaller`) and a class of `org.slf4j.` tells nothing. Not
  matched: an argument whose `toString` logs through SLF4J while the binding formats the
  message (its record takes the inner call's frame).
- **Commons Logging (1.3.6, 1.4.0; m17)** picks as under a JDK: SLF4J beside it, its
  `Slf4jLogFactory` (a constant name, folded, no metadata); else `LogFactoryImpl` (constant)
  and `Jdk14Logger`, found by a name held in a field, which only reachability metadata
  declares (the program's, or `--java-metadata-repository`'s; without it `No suitable Log
  implementation`). A call of `org.apache.commons.logging.Log`'s `trace` .. `fatal` tells the
  caller, a class of `org.apache.commons.logging.` tells nothing (`JavaLowering.facade`).
  **Trap: telling is not enough for `Jdk14Logger`**: it finds the method on its own stack
  (`new Throwable().getStackTrace()[2]`, "unknown" when there is none, as here) and hands it
  to `logp`, whose source wins over the told one. So a facade class's call of `logp` gives
  `Logger.toldMethod(<its argument>)`, the told method where one was told; the class it
  gives is the logger's name under a JDK too. Rejected: a told stack from `getStackTrace()`
  (the frames between are not known; every program asking a stack would read logging's
  state).
- **A program whose entry points are exports** (a `--no-wasi` reactor, `@WitExport` classes)
  initializes its launched class at its first use (`startsNowhere`): `JavaReactorTest`'s
  logger field, read in `greet`.
- **Sizes** (2026-11-25): byte-identical without the flag; with it the console's formatter
  leaves (`Logger.getLogger("app").info("started")` as a component 345,980 -> 226,800 B).
  SLF4J's four calls are 636,235 B of Preview 1 (522,125 as a `--wasi-logging` component),
  `%class-members` 23 KB of it: the explicit provider's `getConstructor().newInstance()` stays,
  as the `slf4j.provider` property no target has is read through a helper, which the property
  question does not see. Commons Logging 1.3.6's four calls (2026-11-25, m17; SLF4J's same
  program 615,801 / 505,133 then): 1,212,894 B over `Jdk14Logger` (1,201,696 as the
  component), 1,211,683 over SLF4J (1,200,312) -- `LogFactory` and `LogFactoryImpl` are in
  whichever runs, the factories being constant names; `%class-members` 29,462 B,
  `String%jdk-methods` 11,674. Trap: a program run from a directory carries the directory as
  resources once `LogFactory` names a resource it computes (`commons-logging.properties`), so
  outputs written there grow the next compile (12.6 MB measured).
- **Pinned by** java-spec
  `a-program-logs-through-slf4j-and-commons-logging-and-the-build-says-where-the-records-go`
  (Commons Logging through SLF4J and through `Jdk14Logger` by the program's metadata; the five legs;
  `hostLog`: the `--wasi-logging` component composed by `wasm-tools compose` with
  `testsupport/wasi-logging-host`, a WAT host, `JavaSpecE2eTest.hostLogged`; CI's `test-e2e`
  installs wasm-tools 1.263.0), `JavaReactorTest` (the Worker route under node),
  `RontoLispCliTest` (the refusals).

## java.io (2026-11-17, j79)

**Invariant: a stream is Java source over `byte[]`, `char[]` and the stream under it; the
host is reached at three classes only -- `StandardOutput`, `StandardInput` (`java.io`, not
public) and the UTF-8 pair of `java.lisp` -- and a program that only prints holds none of
it.** The classes: `doc/en/java/classes.md`; files: "Files" below.

- **`System.out`, `System.err`, `System.in` are two things** (`JavaLowering.directReceiver`):
  the host's stream as the receiver of a print or a `flush`, in a method reference or through
  a static import, and a `PrintStream` made at the first ask as a value
  (`xs.forEach(System.out::println)` 67,439 B of wasm, 71,120 through the object). Rejected:
  a `PrintStream` intrinsic holding the host's stream (j68: no `OutputStream`, so `new
  PrintWriter(System.out)` has no type); `System` as source (every program would hold the
  class and its initialization).
- **`StandardOutput`** buffers nothing, nor do the direct forms, so their lines keep their
  order; `close()` flushes and leaves it open (`deviations.md`).
- **`StandardInput`**: a read of several goes on while `%java-input-more` says so: `listen` on
  the interpreter and the JVM, to the line's end on wasm, where `listen` of the standard
  input traps (measured, wasmtime 49 and 51.0.0); so a bulk `System.in.read(buf)` mixed with
  another reader splits otherwise on wasm (`deviations.md`).
- **A `PrintStream` a program makes** keeps an `IOException` for `checkError()` by a
  `try`/`catch`, so making one costs the handler. The writers' 8,192-unit buffers are a JDK's.
- **UTF-8** is decoded by `%java-utf8-string`, not the host's `octets-to-string`, which takes
  what is no UTF-8 as it comes. The U+FFFD rules, measured against JDK 25 (6,000 random
  sequences over the bytes that matter, identical through `new String` and a reader): a byte
  starting nothing is one; a start whose second byte is outside its range (`E0`: `A0`..,
  `F0`: `90`.., `F4`: ..`8F`) is one, alone; a sequence cut by a byte or by the end is one;
  the three bytes of a surrogate are one.
- **`LineNumberReader` counts as JDK 25 does, measured** (1,900 random scripts of reads,
  skips, marks and resets over it and the three readers beside it; a grid over ten inputs).
- **A `BufferedReader` standing directly on `System.in` takes its line from the host**
  (`%java-readln`; `Reader.hasLines`, answered only by an `InputStreamReader` over
  `StandardInput`, so a program with none holds none of it): 200,000 lines summed, wasm 1.28
  -> 1.06 s (`IO.readln` 0.89), the JVM 0.62 -> 0.44 s, for 143,022 -> 147,639 B of wasm.
- **A charset is its codec, and a program holds the ones it names** (j98): the UTF-16 ones
  are objects of a subclass, `rontolisp.internal.Utf16`, only in a program making one;
  `StandardCharsets`' fields are read through static methods, so `UTF_8` initializes nothing
  of UTF-16; a charset NAMED BY A CONSTANT is the charset (`Charset.forName` lowered where it
  is called; `JavaLowering.charsetsNamed`): `"UTF-8"` named three ways 143,937 -> 132,911 B
  of wasm, a computed name in `getBytes` 64,129 -> 77,465 B. Rejected (j87): the charset
  chosen at run time put the UTF-16 codec in every program with a reader or a named charset
  (143,022 -> 161,075 B).
- **UTF-16's rules, measured against JDK 25** (186 grid lines and 3,000 seeded sequences,
  identical on the four backends; the rules: `Utf16`): a high surrogate followed by a unit
  that is no low one is ONE U+FFFD for both; one followed by one byte or by nothing is one; a
  byte or a surrogate with no partner is one. A reader keeps back an odd byte and the last
  high surrogate of an odd run ending its bytes (keeping any splits `D83D D83D DE00`
  otherwise than the JDK). The mark's state is no slot: a reader, a writer and a
  `PrintStream` swap their charset for the rest's, so `PrintStream.charset()` after the first
  print is another object named `UTF-16` (`deviations.md`). A writer closed on a high
  surrogate writes `?`, U+FFFD in UTF-16.
- **Open**: a string holding a surrogate with no partner encodes to its three bytes where a
  JDK writes `?` (`.todo/k95`). A pair's halves appended one `char` at a time are one
  character on every backend (a JDK: 2 units); a string constant holding a lone surrogate
  keeps it on wasm ("The copies of `copyOf` and the halves of a surrogate pair").

## Files (2026-11-21, k86)

**Invariant: `File`, the file streams and readers, `RandomAccessFile`, `Path` and `Files` are
Java source answering what a JDK 25 answers on a Unix host -- each path, each refusal's
exception and words, a directory's names in the host's order -- over one class of `native`
members, `rontolisp.internal.Disk`, and a program that reaches no file holds none of it.**
Written from the API documentation and JDK 25's answers to probes
([java-frontend-provenance.md](java-frontend-provenance.md)), each run under the JDK and
the four backends, each in a directory holding `locked.txt` (mode 000) and `ro/` (mode 555).

- **The host layer**: `Disk`'s natives are `%java-file-*` of `java.lisp` over Common Lisp's
  file functions and the host's `stat` ("What l67 added"). Measured on the four backends: an
  `:io` open of a missing file with `:if-does-not-exist :create` fails on both wasm ones (the
  file is made first); a directory opens for reading on the interpreter and wasm (the Java
  asks its kind first). A write reaches the file before it answers (`finish-output`), as a
  JDK's unbuffered `FileOutputStream`.
- **readdir's order** is a JDK's `File.list()` and `Files.list`, and every leg's
  (`WasmLispCompilerIntegrationTest#directoryListingResumesPastOneReaddirRound`,
  [directory-listing.md](directory-listing.md)); ext4 hashes names with a seed of each file
  system's own, so no expected text holds it: the corpus sorts what it lists, the wasm test
  compares the JVM's `File.listFiles()`. Also pinned below the layer: the interpreter's
  `read-byte` at the end (ci-spec `file-position-round-trips-on-a-binary-file-stream`,
  [read-load-streams.md](read-load-streams.md)).
- **A `.wasm` sees a file it may not open** through the host's `stat` (`path_filestat_get`
  needs no open), not `%probe-file`, which opens what it probes: wasmtime (49, 51.0.0)
  refuses a mode-000 file even an open asking no rights (measured). Lisp's `probe-file` is
  unchanged.
- **Paths** (`FilePaths.java`, `NioPaths.java`: every line a JDK's on four backends but its
  helpful `NullPointerException` texts): on a `.wasm` `getAbsolutePath` is a JDK's where the
  first preopen has an absolute name (`--dir d::d`, the native runner's), relative under
  `--dir .`; a NUL is `Invalid file path`; `relativize` refuses a `..` it would climb, the
  paths as given: `Unable to compute relative  path from ..` (two spaces).
- **The refusals** (`FileOps.java`, `NioFiles.java`, `Odds*.java`) are a JDK's words, `Stream
  Closed` with its capital C; a move names both paths (`a -> b`), `createDirectories` under a
  file the absolute path.
- **Streams**: the component reads nothing past a file's end until a seek (measured), so a
  `FileInputStream` read at the end asked again seeks to read what the file has grown by; a
  shorter `RandomAccessFile.setLength` writes the kept bytes into the file emptied (no
  truncate below).
- **Strict codecs** (`Malformed.java`, `Malformed2.java`, `Malformed3.java`): `Files`' readers
  and a `Scanner` of a `File` refuse what is no text, `Files`' writers what the charset cannot
  write, counting a refusal's bytes as a JDK does (read off every lead byte before every
  kind of byte, identical on the four backends: `Charset.malformed`). A lone surrogate is
  `MalformedInputException(1)` to a writer and in US-ASCII and UTF-16,
  `UnmappableCharacterException(1)` to `writeString` in UTF-8 and ISO-8859-1.
- **A `Scanner`'s reads were measured and left** (`Odds2.java`): past its first read a JDK's
  asks for the room its buffer has (1024, then 1020), ours for 1024, so a `Scanner` of a
  `File` whose bytes stop being text past 1024 characters gives the tokens before another
  read (`deviations.md`). Asking as a JDK does: +1,021 to +1,444 B of wasm in every
  `Scanner` program (`ScanLine`, `SumScan`, `ScanDelim`), for that case alone. Not made.
- **In the browser** a Java program whose forms reach a `%java-file-` function is refused
  before it runs (`PlaygroundRepl.refuseFiles`, `JavaLibrary.reachesFiles`), a whole program
  and a session's buffer alike: the page's interpreter would answer every file missing, and
  so would a module it compiles (`BrowserModule`'s `path_open` is `ENOENT`).
- **Measured** (2026-11-21; `sizes/` of the artefacts): 34 programs that reach no file and
  `examples/java/shop` are byte-identical; `readString` of 8.3 MB takes 3.10 s on wasm, 0.77
  on a JDK: the decoding and the text scan, Java of the subset.
- **Pinned by** cases run in their directory (`directory: true`; `modes:`, its legs skipped
  for root; `links:` for an absolute link, which no WASI leg makes): java-spec
  `a-program-makes-reads-lists-and-is-refused-its-files-through-java-io` and
  `a-program-makes-reads-copies-moves-lists-and-is-refused-its-files-through-java-nio-file`,
  `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram` (the
  browser's refusal), `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles` (the classes and
  their `throws` against a JDK's).

### What l02 added (2026-11-24)

Written from the API documentation and JDK 25's answers to probes
(k86's legs
with `lockd/`, mode 000): `Matchers.java` (globs, `FileTime`, `file:` URIs) and
`FilesMore.java` a JDK's on four backends but where said.

- **URIs** escape as a JDK's (RFC 2396's excluded characters as `%XX` of UTF-8; past ASCII
  `toUri` every character, `toURI` a control or a space character only), unnormalized;
  `Path.of(URI)` / `new File(URI)` refuse in a JDK's order (`Missing scheme`, `URI is not
  absolute`, ..). On wasm a URI of `..` above the preopen has no slash (the host cannot see
  the directory).
- **`FileSystems.getDefault()`** (`rontolisp.internal.LocalFileSystem.HOST`): `getPathMatcher`
  takes `glob:` and `regex:` in any case, else `Syntax 'x' not recognized`.
- **Globs** (`rontolisp.internal.Glob`, a backtracking matcher, so `newDirectoryStream(dir,
  glob)` holds no regex engine) refuse at a JDK's indexes (`Missing ']` at the last, `Invalid
  range` at a backwards range's first character, ..); an empty set (`[]`, `[!]`, `[]a]`) is
  `Unclosed character class` at the `]`, where a JDK refuses it in words of the regular
  expression it makes (`deviations.md`).
- **`FileTime`**: huge values (`from(Long.MAX_VALUE, DAYS)`, `Instant.MAX`) saturate
  otherwise than a JDK's; not probed.
- **Attributes**: the host's `stat` ("What l67 added" below); `readAttributes` of a class
  takes `BasicFileAttributes.class` alone.
- **`walkFileTree`** lists a directory before `preVisitDirectory`, so one that cannot be
  listed is `visitFileFailed` with no pre or post (a JDK's).
- **Temporary files**: `File.createTempFile` keeps the prefix's last name (`a/bc` makes
  `bcN`) and refuses a prefix under 3 characters (`Unable to create temporary file,
  <name>`); `Files`' refuses a slash (`Invalid prefix or suffix`).
- **Options**: `DELETE_ON_CLOSE` deletes at the open, as a JDK on Linux (measured);
  `ATOMIC_MOVE` is the host's rename after the kinds are checked, so every leg answers
  rename(2)'s words (the interpreter's `%rename-file` is `Files.move` with
  `REPLACE_EXISTING`); `copy` with it is `Unsupported copy option: ATOMIC_MOVE`. A move the
  host refuses names both paths (`AccessDeniedException: a -> ro/a`, java-spec's nio case,
  "move read-only").
- **`user.dir`** named by a constant is `%java-file-working-directory` where it is called, so
  `Support.property` carries nothing of the files (`getProperty("user.dir")` 47,501 -> 2,356
  B of wasm), but a library's `getProperty("user.dir") == null` is not answered where it is
  compiled; the browser refuses a program asking it. `java.io.tmpdir` stays null though
  `createTempFile` defaults to `/tmp`: answering `/tmp` failed
  `FetchSpecE2eTest#javaHttpServer`'s `/app/upload` on the component (500), servlet-httpserver
  putting a part past its threshold there and a served component having no file system
  ([servlet-httpserver.md](servlet-httpserver.md), "Storage").
- **Measured** (`sizes.txt` of the artefacts): k86's 34 programs and `examples/java/shop`
  byte-identical; `newInputStream` asks an option by name, not by `==` (+2,673 B:
  `StandardOpenOption`'s initialization).

### What l67 added (2026-11-25)

Written from the API documentation and JDK 25's answers to probes
(l02's legs with links): `Kinds.java`, `Views.java`, `Walks.java`, `Links.java` and
`Times.java` a JDK's on four backends but where said; l02's `FilesMore.java` differs only in
lines with nothing of a stat (2 on the interpreter and the JVM, 3 on wasm).

- **`%file-stat` (and `%file-set-times`), one primitive per backend**: `stat` (or `lstat`)
  with no file opened, the time of making nil on both WASI backends, where `Disk.stat` takes
  the time of last writing (`.kb/read-load-streams.md`). So a `.wasm` sees a mode-000 file,
  and sizes, nanosecond times and links are the host's (a JDK on Linux: the birth time
  `statx` gives).
- **The words, read off a JDK**: a stat's ENOTDIR is `NoSuchFileException` where an open's is
  `Not a directory`; ELOOP is `FileSystemException` with `Too many levels of symbolic links or
  unable to access attributes of symbolic link` in `java.nio.file` and `(Too many levels of
  symbolic links)` in `java.io`; `notExists` is true only for ENOENT. `Disk.refused` asks
  `stat`'s why, not the parent's listing, which calls a read through a dangling link
  `Permission denied`.
- **The basic view by name** refuses in a JDK's order (the view, `View 'x' not available`,
  then each name, then the file); its map is a `HashMap` filled in a JDK's order (each pair
  of names shares a bucket); `creationTime` is not written (a JDK on Linux).
- **Writing times**: a JDK's `Files.setLastModifiedTime` and `setAttribute` open the file
  first (measured: `AccessDeniedException` for a mode-000 file), so ours do;
  `File.setLastModified` writes by the path, keeping the time of last access to the
  microsecond, as a JDK's `utimes`. On WASI a time is unsigned, below zero written as 0
  (`deviations.md`); wasmtime 51.0.0's Preview 1 reads a pre-1970 time as 0, the adapter's
  signed. For a file the program may not read, the interpreter's and the JVM's
  `%file-set-times` (a `BasicFileAttributeView`, which opens it) write the time of last
  writing alone, in milliseconds, where wasmtime writes both by the path (measured).
- **Links**: a make, a delete, a move and a copy's target see a link as itself, what a copy
  or `isSameFile` reads follows it (`move(link, its target)` is
  `FileAlreadyExistsException`, `copy(link, its target)` a no-op); a walk with `FOLLOW_LINKS`
  reports a directory it is already in as `FileSystemLoopException`.
- **The empty path is the working directory to `File`** (JDK 25, measured): `createNewFile`
  of it is `No such file or directory`.
- **Measured** (`sizes.txt` of the artefacts): byte-identical: k86's 34 programs,
  `examples/java/shop` and Lisp programs reaching files but a `file-write-date` component
  (6,572 -> 6,638: the adapter's errno table).

### Links made and read, and the basic view (2026-11-25)

Written from the API documentation and JDK 25's answers to probes
(`probes/Make.java` and `View.java`, run by l67's `four.sh` with `abs`): a JDK's on four
backends but the helpful `NullPointerException` texts, `fileKey`, the leg's own path and, on
wasm, absolute links.

- **`%make-link` (target, path -> t or nil), one primitive per backend**:
  `Files.createSymbolicLink` on the interpreter (`SourceLoader.makeLink`) and the JVM
  (`_makeLink`), an injected `path_symlink` on Preview 1 (`path_filestat_get`'s type where the
  program has that one), the adapter's over `symlink-at` on the component (one member of
  `core.wat`). The target passes as given, never resolved against a preopen. Reading is
  `%read-link` unchanged.
- **Why, asked afterwards**: the primitive says only whether; `Files` asks as for a file it
  makes (`own` first: `FileAlreadyExistsException`, else `refusal(link, true)`). A JDK's
  order, measured: a null attribute (NPE), any attribute (`UnsupportedOperationException:
  Initial file attributes not supported when creating symbolic link`), an empty target
  (`NoSuchFileException` of the link, whatever is there), then the host's answer.
  `readSymbolicLink` of no link: lstat names a kind -> `NotLinkException`; nothing ->
  `refusal(link, false)` (`Not a directory` under a file, where a stat of the path is
  `NoSuchFileException`).
- **WASI refuses an absolute target** (wasmtime 51.0.0: `path_symlink` and `symlink-at` both
  `perm`, as `readlink` already was; Preview 1 in `wasi/run.p1.txt`, the component through
  the java-spec case). So a `.wasm` makes, reads and copies no such link:
  `AccessDeniedException` (a read: the lstat says a link, the read is refused). A relative
  target climbing above the preopen is made; an empty one is `noent` on both hosts.
- **The basic view** (`Files.View`): of any path, asked when used; another class is null (a
  JDK's for `FileAttributeView.class`). `setTimes` with neither time asks nothing, of a
  missing file too; the time of making is not written; else `given` and `written`, as
  `setLastModifiedTime` (the file opened with `NOFOLLOW_LINKS` too: a JDK refuses a mode-000
  file either way). `given` answers `refusal` whether following or not: `f.txt/x` with
  `NOFOLLOW_LINKS` is `Not a directory` to a JDK.
- **A copy of a link with `NOFOLLOW_LINKS`** reads it and makes it again after the same-file
  check and the clearing, with none of its times (a JDK's, `COPY_ATTRIBUTES` too).
- **Measured** (`sizes/sizes.txt` of the artefacts): byte-identical: k86's and l02's
  programs, `examples/java/shop` (wasm and component), l67's Lisp programs (wasm, component,
  class); `Files.copy` +217 B of wasm, its link arm reaching `%read-link` and `%make-link`.
- **Pinned by** java-spec
  `a-program-makes-reads-copies-moves-lists-and-is-refused-its-files-through-java-nio-file`,
  which makes its own links; `links:` keeps `links/abs: /`, the link no WASI leg can make.
- **Not there**: `Files.createLink` (a hard link) -- a todo item.

### What l68 added: permission bits and the space of a disk (2026-11-25)

Written from the API documentation and JDK 25's answers to probes
(`Modes.java`, l67's legs under umask 022 with `x.sh` 755, `wo.txt` 200 and `wd/` 300). The
interpreter and the JVM answer every line a JDK's but its helpful `NullPointerException` and
`ClassCastException` texts (3 of 88).

- **The decision: two backends answer, a `.wasm` answers as a JDK does on a file system with
  no POSIX view.** WASI preview 1 and `wasi:filesystem` have no mode bits, no `access`, no
  `statvfs`, so nothing a `.wasm` asks can say them. Refusing the members there would refuse
  every program naming one; instead each answers what the specification allows where the
  host cannot tell: `getPosixFilePermissions`, `setPosixFilePermissions` an
  `UnsupportedOperationException` (no message), a `posix:permissions` `FileAttribute`
  `'posix:permissions' not supported as initial attribute`, `canExecute` / `isExecutable`
  false, `canWrite` / `isWritable` of a directory false ("access cannot be determined" is
  false in `isWritable`'s specification), `canRead` / `canWrite` of a file whether it opens,
  the setters false ("the operation failed"), the space 0 ("the size cannot be obtained").
  `Disk.modes()` (`%java-file-modes`, `#+rontolisp-wasm` nil) is asked first, so the order of
  a JDK without the view holds (the view before the file).
- **The host layer**: `%file-stat`'s list has a sixth element, the mode (nil on both WASI
  backends). Four primitives on the interpreter and the JVM only, as `%run-program` is:
  `%file-access`, `%file-space`, `%file-set-mode` (`Files.setAttribute(p, "unix:mode", m)`,
  which keeps 07000) and `%file-make` (with a `PosixFilePermissions` attribute, so the host
  applies the umask). One class, `runtime/RontoFileModes`, answers them for both, the JVM by
  `invokestatic` with the class beside a program that calls one
  (`JvmLispCompiler.FILE_MODES_RUNTIME_CLASS_FILES`). `java.lisp`'s `%java-file-*` are
  constants under `#+rontolisp-wasm`, so no WASM backend compiles a name it lacks.
- **What a JDK does, measured** (the bits as `File` and `Files` state them): `setReadable` ..
  keep the bits not asked (07000 too), false where nothing is there; `setPosixFilePermissions`
  gives exactly the nine (07000 cleared), follows a link, refuses as a stat does
  (`NoSuchFileException`, the loop words, `AccessDeniedException`). A file made with an
  attribute has its bits less the umask, the last attribute winning; `createDirectories`
  gives each directory it makes the bits, and names the level refused, absolute
  (`AccessDeniedException: /abs/ro/x` for `ro/x/y`, with or without bits).
  `Files.createTempFile` is `rw-------`, `createTempDirectory` `rwx------`;
  `File.createTempFile`, `createNewFile`, `mkdir` the host's default. A copy has the
  source's nine bits less the umask (`rwxrwxrwx`: `rwxr-xr-x` under 022), `COPY_ATTRIBUTES`
  the source's mode exactly (a setuid bit too), and a source the owner may not write copies
  all the same. `PosixFilePermissions.fromString` refuses anything but nine characters each
  its letter or `-` (`Invalid mode`); the sets are `EnumSet`s in the enum's order (a JDK's
  `asFileAttribute` value is a `HashSet`, whose order is the enums' identity hashes: not
  reproducible).
- **The test of a deviation**: java-spec's `wasi:` (a map of a line's label to its text on
  both WASI legs; `JavaSpecE2eTest.Standalone.stdout(leg)`, every label must be a line) pins
  what a `.wasm` answers where it cannot answer a JDK's. The two file cases carry the
  permission lines with it; the bits printed are of `r-xr-x---`, `rw-------` and
  `rwx------`, which no umask of 022 or 002 changes, as the JDK leg runs under the test's.
- **Measured** (wasm bytes; `sizes/sizes.txt` of the artefacts): byte-identical: k86's 34
  programs that reach no file, `Prop`, `UserDir`, l67's `Hm` and `examples/java/shop` (wasm,
  component, class) and l67's Lisp programs (wasm, component, class). Java file programs: +5
  (`Path` arithmetic) to +587 B (a temporary file, its `rw-------`), `Files.copy` 192,533 ->
  194,992 (the bits it gives). New: `getPosixFilePermissions` and `setPosixFilePermissions`
  192,406, `getUsableSpace` 52,690. A `static final` of `Disk` is a global in every program
  reaching the class, so the selectors of `access` and `space` are literals.

### The owner, posix and unix views (2026-11-26)

Written from the API documentation and JDK 25's answers to probes
(`.todo/artefacts/m27-owner-posix-unix-views/`, its `README.md`): the interpreter
and the JVM answer every line a JDK's but the helpful `NullPointerException` and
`ClassCastException` texts and `fileKey`.

- **Two backends answer, a `.wasm` has no such views** (as the permission bits are): WASI has no
  owner, `getOwner` / `setOwner` / `readAttributes(p, PosixFileAttributes.class)` are an
  `UnsupportedOperationException` (no message), the views by name `View 'posix' not
  available`, `getFileAttributeView` null, `supportedFileAttributeViews` `[basic]`. A JDK's
  zip file system, its one without the views, says `view <posix> is not supported` and
  `Attributes of type .. not supported`: provider words, not the default provider's.
  `dos` and `user` are absent on every backend (`supportedFileAttributeViews` names
  `[owner, basic, posix, unix]` where a JDK on Linux has `[owner, dos, basic, posix, user, unix]`).
- **The host layer: two primitives of the interpreter and the JVM only**, beside the four of the permission bits in
  `RontoFileModes`: `%file-unix path follow names` (the unix view read in one call: `uid gid
  ino dev rdev nlink ctime mode`, the mode WITH the kind's bits, then the owner's and the
  group's names where asked) and `%file-set-owner path follow uid gid` (`unix:uid` /
  `unix:gid`, -1 leaving one). Not `%file-stat`: every file program asks a stat, and the
  numbers and names are asked only by the programs that name a view (Disk.unix / owners /
  setOwner). A name is the host JDK's `getpwuid` answer (the number where the user database
  has none), so no backend reads `/etc/passwd`. Why 5 is `EPERM` (`FileSystemException: f:
  Operation not permitted`, a chown or a chmod of another's file).
- **What a JDK does, measured**: a principal's hash is its uid or gid, it equals one of the same
  kind and number (an owner never its group of the same number), `toString` and `getName`
  the name. `setOwner` refuses null (`NullPointerException: 'owner' is null`, `setGroup` too),
  then a principal of another class (`ProviderMismatchException`), then a group
  (`IOException: 'owner' parameter can't be a group`), then the path. chmod follows a link
  even with `NOFOLLOW_LINKS` (Linux has no lchmod; a dangling link `NoSuchFileException`);
  chown with it is lchown. `posix:permissions` and the view's `setPermissions` give the nine
  bits (07000 cleared), `unix:mode` all 07777. Names refused: `'uid' not recognized` for the
  basic, posix and unix views, `'owner:size' not recognized` for the owner view and every
  `setAttribute` (`'unix:ino'`). The owner view's map is a plain `HashMap` (a JDK's: `put`
  works), the others unmodifiable. The maps' iteration is a `HashMap` filled in the order
  basic, `permissions owner group`, `mode ino dev rdev nlink uid gid ctime`: 3000 random name
  sets of the three views against a JDK, no difference.
- **`fileKey` stays null**: a JDK's is `(dev=<hex>,ino=<decimal>)`, its hash `(int) (dev +
  ino)` (measured with `/proc/self/ns/net`, ino 4026531840); `.todo/m56`.
- **Measured** (wasm bytes; the artefacts' `sizes/`): byte-identical (wasm, component,
  class): a program of no file, one of collections, `examples/java/shop`, one reading and
  writing through `Files`, one of `java.io.File`, a Lisp program of files. The slots of
  `Disk.unix` and EPERM's why live in `Files.Unix`: as `Disk` static finals they cost every
  file program 79-87 B of wasm and 2,092 B of class. `getPosixFilePermissions` and
  `setPosixFilePermissions` 256,381 -> 256,397; with the basic view by name too 329,595 ->
  343,110 (the views' own code); the basic view by name alone 268,969 -> 340,630, the
  `EnumSet` a `posix:permissions` value is making the program reach `getEnumConstants`, so
  every enum's `values()` (`.todo/m57`). `getOwner` alone 175,843.
- **Pinned by** java-spec
  `a-program-makes-reads-copies-moves-lists-and-is-refused-its-files-through-java-nio-file`
  (`views` .. `unix set refused`, each with its `wasi:` line). EPERM and a foreign principal are
  probes only: the legs run as whoever runs the test.

### deleteOnExit and shutdown hooks (2026-11-25, l69)

**Invariant: a program that registers something for its end -- `Runtime.addShutdownHook`,
`File.deleteOnExit` -- does it at every end there is but `halt`, on four backends; a program
reaching neither is byte-identical to what it was.** `rontolisp.internal.Shutdown` (Java
source) holds the hooks and the paths; `JavaIntrinsics.ends` tells whether
`Shutdown.addHook` or `Shutdown.deleteOnExit` is reached, a watched member, so the program
is lowered again when one is. A program starting threads enters through
`%java-thread-ending`, its end run once the threads that are no daemons ended; on wasm that
end is a body of its own on main's carrier (it waits for the hooks), then the threads take
their turns again; not while a server serves. `%java-thread-main` takes no optional end:
that changes every program starting threads.

- **Measured under JDK 25**
  (probes): hooks run
  after the last thread that is no daemon, after the uncaught report, on the thread calling
  `exit`; on threads of their own (`Thread-N` named as made); one that throws is reported as
  its thread and the status stays; files go after every hook, the path registered last
  first, a path once at its first place (`LinkedHashSet`: a, dir, a leaves dir), a NUL path
  ignored, one a hook registers deleted too; a hook the program started or that ended is not
  started again (no message) and is waited for; `halt` runs nothing. `add` checks: `Shutdown
  in progress` (before the null), the null (`isAlive`'s NPE), `Hook already running`, `Hook
  previously registered`; `remove`: `Shutdown in progress`, then a bare NPE. **`exit` in a
  hook waits for ever** (a JDK hangs, measured to 120 s): here the hook's `exit` writes a
  line and halts with 1 on four backends (`deviations.md`); an `exit` on another thread
  during the end waits for ever, as a JDK's.
- **Hooks are threads** (an interface `Hooks` made by the first `addHook`, so a program with
  only `deleteOnExit` starts none), started in the order added -- a JDK's order is its
  identity map's -- then joined.
- **Sizes** (2026-11-25, wasm / component / class bytes; `sizes/` of the artefacts):
  byte-identical: `Fib`, `System.exit`, `Runtime.exit`/`halt`, an uncaught exception, a
  counter under four threads, `main` dying under a thread, a `FileReader` program (an
  `import` of `Shutdown` in `File.java` reordered the classes of every program reaching
  `File`: the name is written in full). `deleteOnExit` alone 100,035 / 106,777 / 133,644, one
  hook 135,846 / 146,841 / 174,632.
- **Pinned by** java-spec's `the-shutdown-hooks-run-and-the-files-go-at-system-exit`,
  `the-program-ends-when-its-threads-have` (a hook after the threads and the uncaught report)
  and the java.io case's `delete on exit` (`exists:`, which `JavaSpecE2eTest` checks on every
  leg); the hook's `exit` by
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot`.

## java.util.stream (2026-11-19, k12)

**Invariant: a stream is a function answering its next element, each stage a closure over
the stage before it; nothing runs until the last operation pulls, and what a JDK 25 shows
of a pipeline -- which stage sees which element when, what `count()` skips, the order of
a collected map -- is what the corpus pins.** Written from the API documentation and
JDK 25's answers to probes ([java-frontend-provenance.md](java-frontend-provenance.md)).

- **The shape** (`rontolisp.internal.Flow`): a stage holds a `Pull`, a functional interface
  only lambdas implement (a pull is a `funcall`), and an `Ending` the pull sets when it has
  no element -- no sentinel, so `null` is an element and an `int` needs no box. Rejected: an
  `Iterator` class a stage (two dispatched calls an element: 50 to 65 ns on wasm), a sentinel
  (a cast a stage, a box an `int`), sinks pushed into (no `iterator()`, no lazy `flatMap`).
- **Laziness and `count()` are a JDK 25's** (`Lazy.java`; `Sized.java`, 114 sources and
  stages, 102 rows in the corpus), but for a PARALLEL stream's `sorted().skip(n).count()`,
  which runs under a JDK. **Everything runs in order on the calling thread**: `parallel()`
  only sets the flag, a combiner is never called (`deviations.md`; halves: `.todo/k95`). A
  stream is asked once (the JDK's `IllegalStateException` and words); close handlers run
  once, in order, the first failure holding the others suppressed.
- **A stage is told that no more is asked** (`Flow.Told`), in a JDK 25's order (`Multi.java`
  of the k26 artefacts); `limit` and `takeWhile` tell only the stages before them, so a
  gatherer after a `limit` is not finished as rejected. Left as measured: a later stage that
  throws closes nothing (a `try` / `finally` in every operation ending a stream would put the
  exception machinery, 47 KB of wasm, in every stream program); `iterator()` and
  `Stream.concat` take an inner stream an element at a time, a JDK whole. A `flatMap`
  program pays 1,124 B of wasm.
- **`mapMulti` and a gatherer hold what they give for one element**, where a JDK pushes each
  through the later stages while the function runs: a `peek` after `mapMulti` sees the
  outputs after the function returned, and a gatherer's `push` answers `true` until the
  pipeline said `LEAVE` (a JDK's turns mid-element), so a gatherer looping until `push`
  answers `false` does not return here. Rejected: stages pushed into (as sinks above, or the
  library twice).
- **A gatherer** answers as a JDK 25 (`Gather.java`, `Gather2.java`): every state is made
  before the first element is asked for, even under `limit(0)`; the finisher always runs;
  **a gatherer straight after a gatherer is one gatherer**, as `a.andThen(b)`. A JDK's
  `mapConcurrent` may hand a finished result on sooner, by the clock.
- **A double stream's sum is Kahan's compensated one**, the last compensation subtracted:
  no documentation says which summation a JDK uses, and of plain, Kahan, that and Neumaier
  only that gave a JDK 25's bits on all 20,000 seeded sums (`Sum.java`: 9,816 / 19,879 /
  20,000 / 15,002).
- **The collectors**, measured where the documentation leaves it open (`Ch.java`, the
  corpus): the order keys sharing a bucket are walked in, `toMap`'s `Duplicate key ..
  (attempted merging values .. and ..)`, the FIRST of equal elements kept by `min` / `max`,
  `characteristics()`. **Decided: the finisher is always called** and a program's own
  collector's marks are not read: that is `characteristics()` of every collector made -- a
  set and a dispatcher in every program that groups -- for a mark only a `finisher()` that is
  no identity under `IDENTITY_FINISH` would show (`.todo/k95`).
- **A string's `chars` and `codePoints` are one stream, of code points.** `Matcher.results`
  starts where the matcher stands when the stream is READ and leaves it there (measured).
- **Measured** (2026-11-19, ns an element, JVM class / wasm / JDK 25): `Arrays.stream(a).sum()`
  13.0 / 56.0 / 1.8, with three `map` 86.5 / 156.4 / 8.0. **A stage is some 33 ns an element
  on wasm and 16 to 40 on the JVM: a closure call's cost there**, the lead, not the library.
  A program calling no stream holds nothing more
  (`JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`) but reads 97
  library files, not 46 (`Collection` names `Stream`): 1.89 -> 1.95 s for one list on the
  interpreter (`.todo/k95`).
- **`String.compareTo(null)` throws** (23 B of wasm where strings are compared).
- **Spliterators** (`rontolisp.internal.Splits`) take a collection's iterator and size at the
  first `tryAdvance` or `estimateSize`, as a JDK's `ArrayList`; `Iterable.spliterator()`
  takes the iterator at once. What 50 collections report is a JDK 25's (the corpus case
  `a-spliterator-reports-what-a-jdk-reports-of-each-collection-and-a-stream-is-made-of-one`).
  **Decided: nothing splits** (`trySplit` answers `null`, within the specification: a stream
  here runs in order, and how a JDK's `AbstractSpliterator` batches is its own). Left as
  measured: a change while one is read throws before the next element (a JDK's `ArrayList`
  and `HashSet` hand it on first); a program's collection overriding `spliterator()` and not
  `stream()` streams its iterator; a `SORTED` or `DISTINCT` source leaves `sorted()` /
  `distinct()` a stage.
- **The spliterators of numbers** are a JDK 25's (`Prim.java`). **Decided: a stream's
  `spliterator()` reports `ORDERED`, and `SIZED | SUBSIZED` where the stream knows its
  count, and nothing a JDK keeps track of through the stages** (`Split.java`, 29 sources by
  19 stages): kept in one `int` of the pipeline, the marks cost 0.4 to 2.5 KB of wasm in
  every stream program, an `&=` of an `int` field being 221 B of a method every stage
  changing the elements would call. Its estimate
  of an unknown count is `Long.MAX_VALUE` (a JDK's: its source's), and `sorted()` always
  takes every element (`deviations.md`). Lead (`.todo/k95`): the marks only in a program
  asking for one.
- **A `Random`'s streams** draw an element when it is read. A `long` and a `double` between
  bounds are the documentation's draws; for an `int`, which no documentation states, the
  long's draws over `nextInt` gave a JDK 25's numbers on all 2,891 seeded ranges,
  `nextInt(width) + origin` on 2,414 (`Rand.java`); the bounded `next*` draw so too. Bounds
  too far apart for a `double` or a `float` are halved and the draw doubled (`Wide.java`:
  14,000 doubles and 10,000 floats a JDK's; scaling by each bound gave 7,417).
- **A concurrent map's collectors** are also `CONCURRENT` and `UNORDERED`, their arguments
  unchecked for `null` when made, as a JDK's (`Conmap.java`).

## java.net.http (2026-11-18, k03)

**Invariant: a request is the lowered program's own `rontolisp:fetch` call, so every
transport fetch has carries the client and none is new; the API is the JDK's, and the
host's future is never a value of the program.** No class of a package of our own was
needed: what a transport cannot give has no member (refused by name as any missing
member) or is refused where the request is sent (`deviations.md`, "The HTTP client").

- **Where it sits.** Java source (`rontolisp.internal.Http`). `start0` is lowered to
  `(%java-http-exchange (rontolisp:fetch url ..))` in a `handler-case` answering the error's
  `princ-to-string`, so the forms the seam hands on NAME fetch:
  `HostFetchLibrary.processForRunner` and `HttpLibrary` splice their transports,
  `WasmFetchCompiler.reject` refuses a plain Preview 1 `.wasm`, and a program reaching no
  member of the client holds no fetch. A failure comes back as text, thrown by
  `Http.failure`: `RAISED` did not grow.
- **The future travels in a vector.** Trap: a resumable function answers through an
  `await`, which flattens a future it is handed, so a bare one was awaited and the
  scheduler, settling only its own, ended a program with a `BodyPublisher` and a
  `sendAsync` with `every thread .. waits` (the component, `subset`).
- **`sendAsync` hands the rest to `CompletableFuture.supplyAsync`** (the client's
  `executor`, else the common pool, as a JDK 25), so it starts threads; requests are in
  flight at once on every transport (`/held` + `/release`).
- **The wait for a reply is a suspension.** Under a component the thread waits among the
  others ("The host's events give the turns under a component"); in a `--native` module it
  gives one turn, then waits for the runner, which has `rlhttp.head` alone, with no thread
  going on -- not one asleep, not its own server (`.todo/k18`). Rejected: yielding while
  others can go on (a thread spinning on a flag the reply's dependent sets never lets the
  reply in).
- **A deferred future where the state machines run**: in a Preview 1 module whose program
  starts threads, the runner's `%future-deferred` is a `TYPE_FUTURE` that `_future_poll`
  resolves ([async-await.md](async-await.md), "Preview-1 wasm-GC, a module naming .."), so
  threads and HTTP compile together for `--native`.
- **Redirects** are Java (`Client.redirected`): the policy measured for the Clojure client,
  asked again of JDK 25 (a PUT's 302 keeps its method and body).
- **`java.net.URI` is `eval/uri.lisp`** (`eval/UriLibrary`), shared with the Clojure client;
  a base with no scheme or authority resolves too (`a/b` + `c` is `a/c`). From a JDK 25's
  answers (76 references, 18 refusals): an opaque part (`Illegal character in opaque part`,
  `Expected scheme-specific part`), an address in brackets (not its syntax), an authority's
  host.
- **Measured against JDK 25** (the corpus's cases): the builders' refusals and their order,
  `hashCode`, `toString`, a blocking `send`'s exception holding the failure as its cause; a
  refused connection is a `ConnectException` by the transports'
  three texts (`java.net.ConnectException`, `:CONNECTION-REFUSED`, `cannot connect`).
- **`BodyHandlers.ofString()` reads three charsets off `Content-Type`** (UTF-8, ISO-8859-1,
  US-ASCII), else UTF-8: a lookup by a computed name would put the UTF-16 codec into every
  program reading a text ("java.io").
- **Time limits** are fetch's `:first-byte-timeout` and `:connect-timeout`
  ([fetch-http.md](fetch-http.md), "Timeouts"). **Only a program that gives a builder a time
  limit hands fetch the two options**: `Http.limit` makes the `TimedWire` the requests leave
  by, and a class a program never makes is in no dispatcher, so any other program's
  transports carry no timeout arm (a blocking GET's component: 470,546 -> 472,868 B; with a
  limit 489,891). The transports' texts (`request timed out`, `CONNECTION-TIMEOUT`, ..)
  become `HttpTimeoutException` / `HttpConnectTimeoutException` as JDK 25 answered on four
  legs (`Invalid duration: PT0S`; a redirect keeps its timeout). A program's own
  `HttpRequest` stating a `timeout()` is refused by name where no builder was given one; a
  component that serves cannot time a request (`.todo/148`).
- **Pinned by** `FetchSpecE2eTest#javaHttpClient` (`java-http-spec.yaml`: three programs
  under the JDK, the oracle, and on the interpreter, the JVM, `--native` and the component;
  `subset`, the client's refusals, has no JDK leg),
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the fetch form),
  `#theLibrarysSourceIsJavaAJdkCompiles`,
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (the Preview 1
  refusal), `NativeFetchTest` (the deferred future), the `doc/*/java/index.md` example
  (`DocExamplesTest`).
- **Not made** (`.todo/k95`): bodies as they arrive, the `--host-fetch` reactor as a leg,
  `URI`'s constructors of several parts.

## com.sun.net.httpserver (2026-11-19, k04; the threads, the executor, filters and authenticators: k11)

**Invariant: a server is the lowered program's own `rontolisp::%http-serve-begin` call, so
every transport a served application has carries it and none is new; the API is the JDK's;
an exchange answers through Clack's delayed response, whole or through its streaming
writer, which the transport that can sends chunk by chunk -- on the socket legs through
the exchange's reply the delayed function is handed; a request is a task the server's
executor runs, and on one stack a thread of the program's own scheduler; an exchange
outlives its handler, answered by whichever thread ends it, and no thread waits for it.**
No class of a package of our own was needed (`deviations.md`, "The HTTP server").

- **Where it sits.** Java source (`rontolisp.internal.HttpServing`); `com.sun.net.httpserver.`
  is a platform prefix (`JavaClasses.isPlatform`: a class of it the library lacks is refused
  by name). `open0` is lowered to a `handler-case` around
  `(%http-serve-begin (lambda (env) ..) port host)`, so the forms the seam hands on NAME the
  transport: `HttpServeLibrary` splices the target's leg, and under `--component` its
  directive makes a `wasi:http/handler` component (`.kb/clack.md`, "Transport selection").
  On a plain Preview 1 module `create` throws `an HttpServer needs a transport: compile with
  --component .. or with --native`.
- **The two-step serve** (`http-serve.lisp`), since `%http-serve` blocks or cannot be joined
  and a Java program goes on after `create`: `%http-serve-begin` binds at once,
  `%http-serve-run` answers once the server stopped (the join on the socket legs, **the
  serving loop itself on `--native`**, at once under a host). Where the host has threads,
  **the lowering appends `HttpServing.serve()` after `main`**: the thread the program started
  on parks until the last `stop` -- a park, so that the interpreter's `System.exit` on a
  request thread wakes it ("Threads") -- then joins the transport.
- **On one stack a request is a thread of the scheduler's.** Every program reaching the
  server is lowered resumable on wasm ("Sizes" below) and runs while a thread that is no
  daemon lives OR a server runs; where no thread can go on, the scheduler serves: on
  `--native` `%http-serve-step server limit`, waiting at most until the soonest time a thread
  waits for; on a component the host's events give the turns ("The host's events give the
  turns under a component"). **The instance's start ends where `main` returned or every
  thread waits.** So a `main` waiting for ever, a handler waiting for a thread and a
  handler's own `Thread.currentThread()` hold on both wasm targets (`/worker`, `/thread`).
- **A thread outlives the request that started it: `%task-swap`.** Trap: a frame is owned by
  the task current when it is made, and `_wake_list` defers a waiter of another task to that
  task's doorbell -- a task long ended (`every thread .. waits` at the third request). So
  `%java-http-request` runs with no current task ([async-await.md](async-await.md),
  "`%task-swap`"); under a component the swap is the pump's.
- **`wasmtime serve`** (49.0.0, 51.0.0): an instance answers up to 128 requests, 16 at once,
  and is dropped after 1 s idle (`--max-instance-reuse-count`,
  `--max-instance-concurrent-reuse-count`, `--idle-instance-timeout`), so a static field
  counts per instance; one with a thread of the program waiting is not idle
  ([servlet-httpserver.md](servlet-httpserver.md), "Sessions"). Standard output is the host's
  log (`stdout [n] :: ..`); no `Host` field and no peer: the local port is 80,
  `getRemoteAddress()` `0.0.0.0:0`.
- **A request before `start()` waits**, as a connection a JDK has not accepted yet.
  Rejected: binding at `start` (no `BindException` from `create`, and `getAddress()` of port
  0 would be 0 until then).
- **A request is a task; the thread the transport called on waits for nothing.** With no
  executor `Task.work()` runs under the server's `Turn` monitor (one at a time, as a JDK's
  `HTTP-Dispatcher` thread), else `executor.execute(task)` under it. The monitor is a class
  of its own because `JavaResumable.Lock.mayBe` takes two `new Object()` locks for one,
  making every `synchronized (LOCK)` an entry that may block; with no executor the task is
  called by its class, since `Runnable.run`'s dispatcher reaches every `Runnable` a program
  makes. On the host each request binds `%java-carrier` to its
  own carrier (else every request thread WAS the thread the program started on), `bind`
  makes the run time's tables shared (`%java-shared`), and a served program holds monitors.
  The transport's thread is `HTTP-Dispatcher`, with no `Thread` until asked for (rejected:
  an `instanceof` and two casts in `currentThread()`, a `ClassCastException` everywhere).
- **An exchange outlives its handler**, as a JDK's lives until its response ended, whoever
  ends it (`Later.java`: answered 300 ms after its handler returned, by another thread).
  `answer` hands the request over and returns; each transport answers an exchange whenever
  it ends: the socket legs through its REPLY (`RontoHttpServer.Reply`), written whole by the
  thread that finishes it; `--native` by an async frame resumed on the stack of whoever ends
  it, taking other requests meanwhile (2,000 asynchronous servlet requests in hand, where
  nesting the one stack trapped at 800: [servlet-httpserver.md](servlet-httpserver.md), "No
  thread a request in hand"); a component's request task waits for the response's end; a
  reactor's call runs the threads until the exchange ended, answering 500 one no thread of
  the call ends. Pinned by `FetchSpecE2eTest#javaHttpServer` (`/held` by `/release`,
  `/later`; `/silent`, `/short` unanswered on every leg as on a JDK), `JavaLoweringTest`
  (`Server.dispatch` a plain function on one stack).
- **`stop(delay)`**, as JDK 25 answered: `stop(3)` with a request in hand returns once it is
  answered; `stop(0)` cuts it; `stop(2)` by a handler takes 2 s (its own request is in hand);
  an exchange its handler left open is closed with nothing after the delay. `stop` waits for
  no exchange in hand or the delay, then closes the transport's server: draining with none
  in hand ([http-server.md](http-server.md), "A draining stop"), else cutting.
- **Filters, authenticators, canned handlers** are Java source a program naming neither
  lowers none of. As JDK 25 answered the corpus's requests: the filters in order, then the
  authenticator, then the handler (a filter before it sees no principal); a chain run twice
  runs the handler again; `BasicAuthenticator`'s challenge (`WWW-Authenticate: Basic
  realm="..", charset="UTF-8"`), its credentials and the realms it refuses. Its base64
  decoder is RFC 4648's.
- **An exchange answers through Clack's delayed response** (the transports' side:
  `.kb/http-server.md`), whole or, after `sendResponseHeaders(code, 0)`, chunk by chunk
  through the responder's writer. The refusals' words are a JDK 25's (`headers already
  sent`, `response headers not sent yet`, `too many bytes to write to stream`, `insufficient
  bytes written to stream`, `stream closed`, `Stream is closed`); a context is the longest
  path the DECODED request path starts with.
- **Measured against JDK 25**; NOT matched (`deviations.md`): a handler that throws, an
  exchange closed with nothing answered and a body short of its length are a 500 (a JDK
  closes the connection); a write after a chunked close is `IOException: stream closed` (a
  JDK's own `StreamClosedException`). A handler returning with nothing answered leaves its
  exchange open, as a JDK does. Not copied: a stream taken BEFORE the head and written after
  it sends nothing, and a refused `sendResponseHeaders(200, -2)` breaks the chunked send
  after it.
- **A handler that throws after a chunked head.** JDK 25 sends the head and the chunks
  written, then closes with no last chunk; thrown before any head, it closes with nothing.
  A component unwinds the delayed function, which ends a body on its way short
  (`.kb/http-server.md`, "Clack's streaming writer"): `wasmtime serve` 51.0.0 closes with no
  last chunk -- nothing at all where the handler threw at once, the head and `4\r\nsent\r\n`
  where it waited 500 ms first -- and logs `error: hyper::Error(User(Body), InternalError(None))`.
  The interpreter, the JVM and `--native` keep the body, so the throwable is an
  application's failure there: a 500 `Internal Server Error`, `HTTP-HANDLER: handler
  failed: ..` on standard error; a Worker answers the same 500, the report on its log (l76;
  `.kb/http-server.md`, "A failure is the socket transports' 500"). Not done: closing those
  connections with nothing (the runner would need a protocol for it, and a 500 is what the
  uncommitted throw answers). Pinned by `java-http-server-spec.yaml`
  `a-handler-that-throws-after-the-head`; a one-context server's component 430,336 ->
  433,283 B.
- **A handler that sends a request through the client** leaves its instance to the host
  under `wasmtime serve`; in a `--native` module it waits in the runner ("java.net.http"),
  so a program asking its own server from `main` still needs the host's threads
  (`.todo/k18`).
- **Sizes** (k11): a request a thread took the `hello` server's component from 374,807 to
  432,210 B (+15%; `--native` 7,199,696 -> 8,046,832): 17 KB eight more resume bodies, 9 KB
  the monitors, 4 KB the scheduler. Every served program pays it: the hand-over to an
  executor is what reaches `wait`. Not measured: a plain call of the handler in a program
  none of whose threads can wait (`.todo/k15`).
- **Pinned by** `FetchSpecE2eTest#javaHttpServer` (`java-http-server-spec.yaml`, each program
  under the JDK and on the interpreter, the JVM, `--native` and `wasmtime serve`; `/stream`
  reads `seen` on the JDK and the component, `unseen` where the body goes whole; `httpbin`
  is `examples/java/httpbin/src/main/java/httpbin/Httpbin.java`, its seven bodies the JDK's
  byte for byte; `servlet` runs on `servlet-httpserver/`; `guestbook` is
  `examples/java/guestbook/src/main/java/guestbook/Guestbook.java`,
  `.kb/servlet-httpserver.md`), `HttpHandlerTest#stoppableSeamServesJoinsAndStops`,
  `#directiveSupportsTheDelayedResponse`,
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the begin form,
  the scheduler's entry), `#theLibrarysSourceIsJavaAJdkCompiles`, the `doc/*/java/index.md`
  example, `examples/java/http/` (`ExamplesE2eTest`).
- **`InetAddress.getByName` reads numbers and `localhost`**, as a JDK 25 answered (`1.2.3`
  is `1.2.0.3`, `65536` is `0.1.0.0`; `null` and `""` the loopback address). Any other name
  is an `UnknownHostException`: no target has a name service the four share.
- **Trap: an interface's static method is no member of a class implementing it**
  (`Headers.of("Allow", "GET")` resolved to `Map.of`): `JavaLowering.methods` leaves it to
  its interface; `java-spec.yaml`
  `an-interface-has-defaults-static-and-private-methods-and-constants`.
- **Not made** (`.todo/k15`): a body sent as it is written on the interpreter, the JVM and
  `--native`; the values of a field sent twice; a second server; `HttpsServer`
  (`.todo/050`), `SimpleFileServer`; name lookup.

### A server as a reactor: the program a host calls (2026-11-24, l95)

**Invariant: a Java server program compiled `--no-wasi` is the same lowered program on the
reactor leg of `%http-serve-begin`; its `main` runs once at `_initialize`, a request is one
call of the synthesized `handle-request`, and what cannot go on within that call ends the
INSTANCE by a trap.**

- **Nothing of the lowering is the reactor's**: `examples/java/http/Server.java`,
  `hello-servlet`, `hello-mvcfn` and the corpus's `httpbin`, `guestbook`, `mvcfn` and
  `servlet` answer through the generated glue as they stand.
- **The exit of a reactor is a trap, for a Java program alone.** Every Java program names
  `%host-exit` (what `main` left uncaught, `System.exit`, a refusal), which a reactor refuses
  at compile time; so `ExitLibrary.process` makes it `rontolisp::%trap` (`exit.lisp`'s
  `#+rontolisp-reactor` arm) where `JavaLibrary.runs(program)` and the program still reaches
  it after `LibraryDefunPruner`; any other reactor reaching a quit is refused. `%trap` is the
  bare `unreachable`: no `handler-case` takes it (a Java `catch (Throwable)` is one, and does
  take a Lisp `error`) and no cleanup runs, as none runs after a process's exit; the glue
  retires an instance whose call trapped and instantiates anew. Rejected: `%host-exit` in
  `java.lisp`'s reactor variant (both name gates refuse a definition that is no `%java-`
  name); a `%java-host-exit` wrapper the lowering calls (five pinned lowerings and every Java
  output's bytes change for one target).
- **No time passes within a call** (`.kb/wasm-export-no-wasi.md`: the clock is the cell the
  host writes). `JavaLibrary.Variant.REACTOR` (`Features.WASM_REACTOR`, before `COMPONENT`:
  `--component --no-wasi` is a reactor too) differs from the one-stack variant in
  `%java-sleep-until` alone, a refusal naming the wait: where no thread can go on and the
  soonest waits for a time, the instance ends. Rejected: `sleep`'s error out of the scheduler
  (the sleeping thread kept the server's `Turn` monitor: every request a 500 until the
  deadline). So `Thread.sleep`, a timed wait nothing ends and a request waiting for
  another's (`/held`) end the instance; a wait after the answer (`/stop`'s `stop(1)`) ends
  nothing ("An exchange outlives its handler" above). `--host-timer` lifts it: "A server's
  waits through its host" below.
- **An `:async t` import in a module with the state machines** settles to a fulfilled
  `TYPE_FUTURE` (`WasmImportCompiler.emitSettledFuture`): such a module polls `TYPE_FUTURE`
  alone, and a `TYPE_P1_FUTURE` passed `%future-force` unresolved. `octets-both-ways`
  crosses a body as octets.
- **What a Worker's host does to a response** (node 24, `wrangler dev` 4.167.0): a field set
  twice is one line joined with `, ` (`Set-Cookie` apart); a `TRACE` request cannot be made
  a `Request`; no peer and no port (`getRemoteAddress()` is `0.0.0.0:0`); on the envelope
  boundary a string body with no `Content-Type` gets `text/plain;charset=UTF-8`, on the
  streaming boundary none.
- **`java.net.http` is the glue's `defaultHost()` fetch under JSPI**: every
  handler is a lambda `main` registers (the transport's `answer` too), so `anyTakenAsValue`
  makes `handle-request` promising. **Measured sound** (node 24.22.3, 2026-11-25): a fetch in a handler, in
  two `Thread`s it joins and under a fixed pool, six requests at once: the whole wasm stack
  (scheduler, `%java-carrier`, ready lists) parks, the glue's queue admits one call, the
  answers and a shared `AtomicInteger` come out in order; a refused connection is the
  `IOException` the handler catches. Module bytes unchanged; only the glue differs. No other
  thread of the program runs while a fetch is suspended.
  A socket is refused by name (`.. add --host-sockets`) unless the build says so:
  "A reactor's sockets" below.
- **What a Worker offers a socket** (Cloudflare's docs, read 2026-11-25):
  `connect(address, {secureTransport: "off"|"on"|"starttls", allowHalfOpen})` of
  `cloudflare:sockets`, outbound TCP only, `readable`/`writable` Web Streams (writes take
  `Uint8Array`), `opened`/`closed` promises, `close()`, `startTls()` once (the old socket is
  then unusable). Not in global scope and not shared across requests; port 25, Cloudflare's
  ranges, `localhost` and private addresses refused; each open socket counts toward the
  simultaneous-connection limit. Postgres and MySQL through Hyperdrive: the binding's
  `connectionString` (or `host`/`port`/`user`/`password`/`database`), dialled over the same
  `connect()`; transaction-mode pooling (a `SET` lasts one transaction, no session advisory
  locks).
  `SecureRandom`: the server's 500, unless `--host-random`. `wasi:keyvalue` sessions: the
  WIT line is refused. Standard output and error are discarded unless `--host-output`
  (`.kb/wasm-export-no-wasi.md`; the two Workers say it), which shows what ended an instance
  before `handle-request failed: RuntimeError: unreachable`. A wait through the host:
  `--host-timer` (below).
- **`--component --no-wasi`** makes a zero-import reactor component exporting
  `handle-request: func(p0: string) -> string` (582,977 B for `Server.java`), with no clock.
- **Sizes** (`--no-wasi --optimize=size`, raw / `gzip -9`): `examples/java/http/Server.java`
  465,042 / 129,323; `hello-servlet` 801,347 / 198,289; `hello-mvcfn` 1,026,977 / 244,546.
  The first request takes 80 - 120 ms under node 24 (instantiation and `main`), then about
  1 ms; under `wrangler dev` 204 ms, then 5 - 11 ms.
- **The build's clock load-path line** is true (a start may wait for a time); the glue sets
  the clock before `_initialize`.
- **Pinned by** `FetchSpecE2eTest#javaHttpServer`'s `reactor` leg (every program of
  `java-http-server-spec.yaml` compiled `--no-wasi --emit-js-glue` with the host opt-ins,
  `--host-timer` too for a `hostTimer` program, served by node through the generated
  `worker()`; the spec's `reactor:` deviations; skipped: TRACE and the peer),
  `JavaLoweringTest` (the exit arm, the refusal for any other program, the variant naming no
  `sleep`), `HostGlueEmitterTest` (the two Workers' glue), `examples.yaml` (`wasm-reactor`
  legs). By hand under `wrangler dev`: `examples/cloudflare-workers/hello-servlet-one-source/`
  and `hello-mvcfn-one-source/`.
- **Where node has no JSPI the leg of a `hostTimer` program is skipped** (`Server.java`, the
  servlets), as a `keyvalue` one's: `--host-timer`'s waits suspend. Trap: `instantiate` wraps
  what `defaultHost()` marks `suspending` (`env.fetch`, `env.readResponseBody`) in
  `WebAssembly.Suspending` whether or not a call reaches it, and node 22.23.5 (V8 12.4, the
  `ubuntu-24.04-arm` runner) has none: a module importing them answers `500 internal error`
  to every request there (`TypeError: WebAssembly.Suspending is not a constructor`). node
  24.22.3 (V8 13.6) passes.

### A reactor's sockets: `--host-sockets` (2026-11-25, m44)

**Invariant: under `--host-sockets` (`Features.HOST_SOCKETS`,
`JavaLibrary.Variant.REACTOR_SOCKETS`, with `--host-timer` `HOST_TIMER_SOCKETS`) a reactor's
`Socket` is the `--native` shape over the other host: a connect and a read are where a
thread waits among the others (`%java-socket-ready`, `%java-socket-poll`), and where none
can go on the call waits in the host's `tcp.wait` (JSPI), for the soonest time a thread
waits for at most. Nothing of the lowering or of the Java socket classes is the
reactor's; the transport is [tcp-sockets.md](tcp-sockets.md), "A reactor's sockets".**

- **Why not the one-stack shape** (a read that parks the whole stack, as a fetch does): a
  reader thread and a writer thread of one socket would never both run, and a thread's
  deadline would be the stack's.
- **Where the scheduler waits**: `%java-thread-run`'s arm (`#+rontolisp-host-sockets`: with
  `%java-polling` set, the host's wait instead of the refused `%java-sleep-until` -- time
  passes in it, and the glue writes the clock again), and under `--host-timer`
  `%java-http-serve`'s, ahead of the idle: a call whose thread waits for a socket KEEPS the
  module (other requests queue; an idle would not be rung by a socket). So a thread waiting
  for a socket with no deadline holds back a request another thread of the call waits for.
- **A socket ends with its request** (the glue's `worker()`): one kept in a static field
  throws `SocketException: Socket closed: the request that opened it has ended` at its
  next use, a JDBC connection `SQLNonTransientConnectionException` (08006) with that
  cause; a pool has to be the host's (Hyperdrive). `main` cannot wait for a socket (the
  glue names `host.tcp.wait` before the trap) unless `--host-timer`, where the socket it
  made ends with the first request.
- **A `ServerSocket` and a bind** are a `BindException` with the provider's words; a
  failed connect a `ConnectException` with the host's (`Sockets.failure`: "cannot
  connect", a Worker's words for a refusal and for a name not found alike).
- **No settings path yet**: `System.getenv` is null on a Worker, so the JDBC Worker's
  `index.js` hands the Hyperdrive connection string over as a request field
  (`.todo/m11`).
- **Pinned by** `FetchSpecE2eTest#javaHttpServer`'s `peers` (a served program whose
  handlers connect to the test's `TcpLinePeer`: a line, octets, 300,000 octets to the
  end, a deadline and the read after it, two threads on one socket, a half-close, a
  connect limit against `@HELD@`, a kept socket and a listener, the last two the
  reactor's deviations) and `ClPostgresE2eTest`'s reactor test (both variants).

### A server's waits through its host: `--host-timer` (2026-11-25, m01)

**Invariant: under `--host-timer` (`Features.HOST_TIMER`, `JavaLibrary.Variant.HOST_TIMER`)
a reactor's wait is its host's: a wait for a time is `sleep`, the host's timer
(`env.sleep`, holding the module), and a request's call whose threads cannot go on IDLES in
the host (`env.idle(token, at)`), which calls the module for other requests meanwhile, until
`env.ring(token)` or `at`. Calls overlap only there, where the run time holds nothing of a
call's: no thread is mid-body, the scheduler re-reads its state on return, the call holds no
arena staging.** Without the flag every output is byte-identical (measured: `Server.java`,
`hello-servlet`, `dog-fetcher`, a Lisp `sleep` on Preview 1 and on `--no-wasi`).

- **What a thread answers of an exchange is done by the exchange's own call**
  (`%java-http-later`: a function queued in the exchange, the call rung if it idles): any
  thread may answer any exchange, a request's or another's, but only its call has the
  request's body and response at the host (the streaming boundary's imports carry no call
  id). The call runs what is queued between thread turns (`%java-http-serve`). Exchange:
  `#(ended failure responder writer nil queue token idle)`; slot 4 stays nil for the
  shared `%java-http-ended`.
- **One call keeps the time** (`%java-keeper`, `(deadline . exchange)`): an idling call waits
  with a deadline only where it is the soonest; others wait for their ring alone, so a timer
  wakes one call, not every one. A call that returns leaving threads ready, or a time no idle
  call keeps (`%java-hand-on`), rings an idling call to take them. With none idling they wait
  for the next request: the threads take their turns while the host has a request in hand
  (a turn after the last answer: `.todo/m48`).
  The servlet corpus's stop after the last answer goes on nowhere (its `logDeviates`);
  `Server.java`'s `stop(1)` after `/stop`'s answer passes in `/short`'s idle call (its log
  is the JDK's but for the port and `main`'s last line).
- **An exchange nothing answers stays open**, as a JDK's (`/short`, `/silent`: no response
  where the reactor answered 500 without the flag), until a stop that does not drain cuts
  it: `close0` is `%http-serve-stop`, which calls the `%http-serve-cutter` the timer variant
  sets (`%java-http-cut-in-hand`: every call in hand ends, `500` and the host's report
  `the server stopped with the exchange in hand`). Cut, `Server.java`'s `/short` and
  `/silent` leave no call for `main` to go on in after the stop.
- **Rejected: `--reentrant`.** It overlaps calls wherever one parks, a fetch inside a thread's
  body included: another call's scheduler would run while that thread is mid-body
  (`%java-carrier` taken over, its monitors entered by threads whose entry the analysis made
  non-suspending: `%java-unforeseen`). Refused beside `--host-timer`.
- **The module side**: the imports are `rontolisp:wasm-import` directives
  (`eval/HostTimerLibrary`, spliced after the pruner where what is left names `sleep` or the
  run time's `%host-idle`/`%host-ring`: before it, `java.lisp`'s idle gave every Java program
  `env.idle`), so ordinals, types and the shake are
  the ordinary ones; `sleep` lowers to `%host-sleep` (ms, a float) where the directive is
  (`WasmExprCompiler`); the idle wrapper lowers the re-entry guard around its host call and
  raises it after (`WasmImportCompiler`, keyed on `compiler/HostTimer`'s names); every export
  is promising, a sleep reaching one being lowered only after the walk that asks.
- **The glue** (`HostGlueEmitter`, `Surface.timer`): `instantiate` is async and enters
  `_initialize` through `promising` under the queue (main may sleep before `start()`); the
  queue is a lock an idling call hands on and re-takes; `serially(work, state)` gives the call
  its state, `lisp.context` whenever it runs (the worker's request body and chunks); the
  arena is popped to where the first call in hand found it (an idling call holds none of it;
  popping to one's own mark left the gap of an earlier call's return); a trap retires the
  instance (`discard`), and a call idling in it wakes to a refusal.
- **Measured** (2026-11-25, node 24.22.3): `/app/later/timeout` 200 `timed out` in 116 ms;
  `/held` asked 200 ms before `/release` answered in 218 ms, either order of the servlet pair
  answers; a Lisp `(sleep 0.12)` reads 121 ms on the module's clock. Sizes (`--no-wasi`, the
  corpus's flags; raw / `gzip -9`): `Server.java` 955,440 / 251,935 -> 961,277 / 253,414;
  the servlets 1,942,421 -> 1,948,771; `hello-servlet` (`--optimize=size`, no wait at all)
  763,973 / 195,585 -> 768,070 / 197,172, its glue 10,697 -> 15,555 B. A program of no
  server imports `env.sleep` alone.
- **Pinned by** the corpus's reactor leg (`hostTimer: true` on `server` and `servlet`: `/held`,
  `/app/later/held`, `/app/later/timeout`, `/short`, `/silent`, and the logs, read once whole),
  `JavaLoweringTest` (the variant names `sleep`, `%host-idle`, `%host-ring`),
  `WasmImportCompilerTest.aSuspendingImportGuardsEveryExportAgainstReentry` (the idle's guard),
  `WasmHostGlueE2eTest.aSleepUnderHostTimerWaitsInTheHostsTimerAtTheTopLevelAndInACall`,
  `RontoLispCliTest` (the refusals).

## java.net.Socket and ServerSocket (2026-11-21, k98)

**Invariant: a socket is the program's own TCP socket (`rontolisp:tcp-*`,
[tcp-sockets.md](tcp-sockets.md)) on every target that has one; `Socket`, `ServerSocket`,
their streams and `javax.net`'s factories are Java source answering what a JDK 25 answers,
each refusal its exception and words; a connect, an accept and a read are where a thread
waits, so on one stack the program's other threads go on meanwhile.** Theme: JDBC for
PostgreSQL (`.todo/l01`); what pgjdbc 42.7.14 calls decided the scope. Written from the API
documentation and JDK 25's answers to probes.

- **`SocketsLibrary` triggers on a `RONTOLISP::%JAVA-SOCKET-` name too**: the classes are
  Java source over `rontolisp.internal.Sockets`' natives, `%java-socket-*` of `java.lisp`,
  which is spliced after `SocketsLibrary.process`, too late for the textual trigger on its tcp
  calls; `WaitForLibrary` asks `SocketsLibrary.references` (a component's read deadline races
  a `wait-for` timer).
- **A read is one host read**: `%java-socket-read` is `rontolisp::%tcp-read-some` with a wait,
  `InputStream.read`'s "at least one, then what has come" ([tcp-sockets.md](tcp-sockets.md),
  "What has come, in one read"); `available()` the same with no wait, into the stream's 8 KiB
  buffer, which a one-byte `read()` fills too. A stream that read the end answers -1 again
  once closed, as a JDK's (`Ended.java`). Measured 2026-11-24 (`measure/Bulk.java`, ms: a MiB
  through an 8 KiB array / 2,000 round trips of 100 bytes): JDK 5-6 / 42-44, `--native` 42 /
  59, component 47 / 172, JVM 233 / 139, interpreter 2,155 / 1,019; a host call a byte was
  524 / 214, 1,099 / 564, 2,149 / 818, 5,261 / 1,950.
- **A write goes out before it returns**, as a JDK's: every socket target buffers a socket's
  writes until a wait ([tcp-sockets.md](tcp-sockets.md), "Per-backend mechanics"), which two
  threads -- one reading, one writing and then waiting on something else -- would never see
  sent. Each `write` is one send, the segments a JDK's are.
- **A socket is made before its connect**, then connected in place within its time limit
  ([tcp-sockets.md](tcp-sockets.md), "A socket made before its connect"), for pgjdbc's
  `localSocketAddress` and `connectTimeout`: `tcp-connect` has only the OS's limit, some two
  minutes on Linux for a dropped SYN. JDK 25 answered: a failed bind leaves the socket open
  and unbound; every bind failure, a listen's too, is a `BindException` with the host's words;
  a socket bound to every interface answers `0.0.0.0/0.0.0.0` until its connect gives it the
  route's address; a connect past its limit closes the socket, a bound one keeping its port; a
  close from another thread ends a connect, an interrupt does not end a platform thread's.
  A half-close is the host's (the output's after what is buffered), the rest answered from two
  flags as JDK 25 answers it (a write after the output's end is `SocketException: Broken
  pipe`, the host's word on Linux, not asked of the host); an accept's deadline is
  `tcp-set-timeout` of the listener ([tcp-sockets.md](tcp-sockets.md), "Read deadlines"), one
  set before the bind given to the host at the bind.
- **What the JDK answered, kept**: the states and the refusals in their order and words (an
  unresolved address closes the socket, as a failed connect does; another kind of address
  leaves it open). Not matched: a JDK's `getSendBufferSize` is the host's (8,192 fresh,
  1,313,280 connected on Linux); here 8,192 and 65,536 until set.
- **The words of a failure** are the JDK's after the operator's on the interpreter and the JVM
  (`tcp-connect: cannot connect to H:P: Connection refused`); `--native`'s are musl's
  (`Address in use`), which `Sockets.failure` maps to a JDK's class and words; a component's,
  the host's error code (`*sock-last-error*`) in the operating system's words. A name not
  found is an `UnknownHostException` of the host everywhere. A name is looked up where the
  socket connects or listens (no name service the four targets share:
  "com.sun.net.httpserver"); one `createUnresolved` made is refused there, as a JDK's is.
- **A thread's wait for a socket keeps its time limit** (a "general" suspension of
  `JavaResumable`). A component awaits the host's future in a frame of its own. On `--native`
  the runner blocks the module on every call, so the thread parks until the runner says its
  socket has something (`%java-socket-ready`); where no thread can go on, the scheduler waits
  in the runner (`poll(2)` after the writes are sent: [tcp-sockets.md](tcp-sockets.md),
  "--native: the runner's sockets") until the soonest time a thread waits for. **A connect
  waits among the threads too**: begun with no wait, polled for writability, then ended.
  Beside a server of `com.sun.net.httpserver` it is ONE wait for a socket or the next request
  (the runner's server rings a bell polled beside the sockets, a socket first): two waits
  taking turns cost a served handler 10 ms each reply of its database
  ([jdbc-postgresql.md](jdbc-postgresql.md), "In a served program", whose measurement pins
  the scheduler's half); swapping their order was rejected unmeasured: it moves the same wait
  onto a request that arrives meanwhile. No timed spec case: a wall-clock bound (40 round trips
  under 5 ms each) failed at load 64 and 15, measuring the box's load. Preview 1 has no
  socket: its first tcp call is `SocketException: a socket needs the host's network ..`.
- **Sizes** (class / Preview 1 / component / `--native`, bytes): threads and no socket cost 13
  B of Preview 1 (`%java-thread-due`'s polling test), a component nothing. **A string
  conversion of an object of a class the lowering cannot tell keeps every reachable class's
  `toString` and the number printing**: one refusal's text joining an `InetSocketAddress` made
  a blocking client (`measure/Bulk.java`) 248,290 / 179,385 / 319,088 / 4,660,248 against
  175,426 / 112,559 / 265,935 / 4,397,328.
- **A count of milliseconds handed the runner is capped at 2^30 - 1** (some twelve days):
  `setSoTimeout(Integer.MAX_VALUE)` reached an `:s32` import ([wasm-import.md](wasm-import.md))
  and trapped `--native`; pinned by the spec's `the longest` line.
- **Pinned by** `FetchSpecE2eTest#javaSockets` (`java-socket-spec.yaml`: the JDK's outputs on
  the four legs, the component with `-S inherit-network=y`, a plain Preview 1 module's
  refusal; `@HELD@`, a loopback listener whose queue is full, which a connect waits on --
  Linux drops a SYN that finds the queue full, so no peer is needed; two threads noting what
  they saw in one array, the JVM's general array widened from two threads:
  [adjustable-arrays.md](adjustable-arrays.md), "Where another thread can run the program's
  Lisp"), the runner's `tcp::tests::a_wait_answers_the_first_socket_that_has_something` (the
  bell, no clock), `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles`, the
  `doc/*/java/index.md` example (`DocExamplesTest`).
- **The options**:
  every getter answers the field the setter kept, and `TCP_NODELAY`, `SO_KEEPALIVE`,
  `SO_LINGER`, `SO_OOBINLINE`, `IP_TOS` and urgent data reach the host through ONE primitive,
  `rontolisp::%tcp-option` ([tcp-sockets.md](tcp-sockets.md)), only once the socket is
  connected: set before, they are given after the connect (`given()`). Reading the host back
  was not built: it would need a get path on four backends, and the host's answers differ by
  OS (the buffer sizes: a JDK's fresh socket answered 4096 for a set 4096 and 2304 for 0).
  Kept only: the buffer sizes, `SO_REUSEADDR`, `SO_REUSEPORT`, a listener's `IP_TOS`; an
  accepted socket takes its listener's `SO_REUSEADDR` (a JDK's answered `true`) and receive
  size. `IP_TOS` keeps `tc & 0xfc` (JDK 25 on Linux: 3 -> 0, 255 -> 252). `supportedOptions`
  holds the eight (Socket) and four (ServerSocket) standard options; a JDK's adds
  `jdk.net.ExtendedSocketOptions`' five. No `IP_MULTICAST_IF` (no `NetworkInterface`).
  A component keeps nodelay, linger, OOB inline and TOS, and refuses urgent data: so the
  spec's `urgent` program (urgent data apart and inline, linger 0's reset) skips that leg.
  **The supported set is a holder class's and the check is by identity**: a `Set.of` in
  `Socket`'s static fields made every socket program call `hashCode` on options of a class
  the lowering cannot tell, which doubled `measure/Bulk.java` (class 173,542 -> 341,830).
  Measured 2026-11-25, Bulk (no option set) class / Preview 1 / component: 173,542 / 112,487 /
  264,039 -> 178,624 / 114,320 / 267,180 (`given()`, the native, `_tcpOption`); a program
  without sockets byte-identical; a Lisp tcp component +162 B (`%tcp-option` spliced).
- **Not made**: TLS sockets (`javax.net.ssl`, pgjdbc's `sslmode`: `.todo/l15`).

## java.security and javax.crypto (2026-11-21, k99; 2026-11-24, l18; providers and a DRBG's parameters: 2026-11-25, l72)

**Invariant: a digest is the function of its message RFC 1321 (MD5), RFC 1319 (MD2), FIPS
180-4 (SHA-1 and the six of SHA-2) and FIPS 202 (SHA3-224 .. SHA3-512, SHAKE128 and SHAKE256
cut to 32 and 64 bytes) state, an HMAC RFC 2104's and a PBKDF2 key RFC 8018's, computed by
the library's Java source on every compiled target and by the interpreter's kernels
(`eval/DigestKernels`) where the interpreter runs; a name written as a constant makes its
algorithm's class and no other; a SecureRandom's every bit is the host's entropy.** Theme:
JDBC for PostgreSQL, its `md5` and SCRAM-SHA-256 logins (pgjdbc 42.7.14's `com.ongres.scram`
asks a digest, an HMAC and a PBKDF2 factory of SHA-1 .. SHA-512, dropping a mechanism whose
names throw). Written from the standards and JDK 25's answers to probes.

- **A name is looked up in the front end's one table** (`JavaIntrinsics.DIGEST_NAMES`,
  `MAC_NAMES`, `FACTORY_NAMES`, the providers' entries too: `Hashing.names`): every name and
  alias a JDK's providers answer through the public `Provider` API, OIDs with and without
  `OID.`, in a JDK's spelling, the standard name first, matched whatever the case. A constant
  name is its algorithm's class where it is written, so a program naming SHA-256 holds no
  other digest; a constant a JDK has and this library does not (HmacPBE*, PBEWith*, SslMac*,
  DES, DESede, the PBE factories) is refused by name; one no JDK has is the
  `NoSuchAlgorithmException` it would throw; a computed name is looked up where the program
  runs (`Hashing.digestNumber`) and makes every digest's class.
- **Java source or a native: measured** 2026-11-21 (`measure/Login.java`, a SCRAM-SHA-256
  login's client side): a JDK 4-17 ms, 72-81 the first; the library's Java compiled, the JVM
  29-72 ms (261 the first), Preview 1 64-81, a component 63-78, `--native` 55-77; interpreted,
  4.0-5.0 s at load 7, 11.8-15.3 s at load 90, 0.3-0.55 s of it outside the compressions
  (`HiBare.java`). **Decided: the digests are Java source, and the interpreter answers their
  blocks and a whole PBKDF2 with kernels of its own** (`eval/JavaDigestNative` over
  `DigestKernels`, written from the standards; elsewhere `%java-digest-blocks` /
  `%java-digest-derive` answer nil): 7-16 ms a login, 79 ms the first (cl-postgres' ironclad
  147-211 ms on a component, 4.1-4.3 s on a fresh JVM class: [asdf.md](asdf.md),
  "cl-postgres"). A kernel per compression alone would leave the interpreter the half-second of
  rounds. MD2's block and Keccak-f are kernels too.
- **Shapes the numbers chose** (4 MiB through each digest, `measure/Bulk.java`). SHA-1's
  eighty steps are one loop choosing the function and the constant by the step's range: four
  loops of twenty left the five registers dual locals read boxed
  ([wasm-unboxed-locals.md](wasm-unboxed-locals.md)), 350 against 150-194 ms on Preview 1.
  k99's: MD5's rounds four steps at a time, each rotation a constant (wasm 306 -> 125 ms);
  SHA-512's block a static method of its own (the JVM 405 -> 225-240 ms), where SHA-1's and
  SHA-256's measured better in their caller's loop; SHA-512's 64-bit constants 32-bit halves
  in packed arrays (a `long[]` literal cost some 170 B of wasm an element). MD2, RFC 1319's
  byte-at-a-time table walk, is not worked (3,261-3,301 ms on Preview 1, a JDK's 452-462).
- **On the JVM** SHA-512 alone in a program steadies at 86-126 ms, at 231-239 after four
  other digests, whose profiles the shared helpers (`_ivAref1`, `_aref1`, the fused methods
  shared by shape) carry. Hoisting SHA-3's 59 round temporaries out of its loop changed
  nothing (389 ms); a declared local read mostly raw is one `long` slot, a tree over such slots
  computed in place (l73, [jvm-int-fusion.md](jvm-int-fusion.md), "Exact locals"): SHA3-256
  62-112 ms on Graal, 85-203 on C2. Their arrays read and stored raw: SHA-512 Graal 120-132 ->
  91-106 ms, C2 335-504 -> 138-161 ([jvm-int-fusion.md](jvm-int-fusion.md), "Declared arrays").
- **Sizes** (Preview 1 / component / JVM class, bytes): a program naming no digest is
  byte-identical: l72 left `NoDigest`, `RandomOnly`, `Constant`, `Computed`, `Login` and
  `RandomText` so in all three forms, `examples/java/shop` on wasm (324,175 / 335,139; its JVM
  class differs between two builds of one jar, 438,350 / 438,354). `getInstance("SHA-256")`
  45,731 / 47,413 / 83,001; a computed name 126,825 / 130,746 / 263,767;
  `Security.getAlgorithms` 262,474 / 266,751 / 341,554 (every provider's entries); a DRBG made,
  drawn and printed with parameters 142,767 / 147,326 / 181,328. **A constant table is data**:
  an initializer of integral constants is a quoted list of what the packed array stores (a
  negative element as its bits), which `PureBuiltinFolder` folds to a packed literal and wasm
  bakes into data ([array-literals.md](array-literals.md)): 4 B an `int`, where
  `:initial-contents (list ..)` cost some 25 B of `%initialization` an element, 9.4 KB over the
  four classes of a computed name.
- **What the JDK answered, kept**: the texts and the refusals in their order and words
  (`Length must be at least N for SHA-256digests`: the standard name, no space); a derived
  key's password the UTF-8 of its UTF-16 units (a surrogate with no partner `?`, whatever the
  strings' own model does). A factory's first refusal that is no checked exception, whichever
  of its three members meets it (`Shared`), is the cause of that member's words (`Could not
  generate secret key`, `Could not get key spec`, `Could not translate key`), any later one as
  it comes -- a JDK's provider failover (`Cleared3.java`), not where `getInstance` named the
  provider. **A `Mac` chooses its provider late** (`MacFresh`, `MacBroken`): any member but
  `getAlgorithm` chooses it, as does an `init` of a key whose format is exactly `RAW`; an
  `init` of another key, or one the HMAC refuses, before any choice leaves the Mac none: `No
  installed provider supports this key: <class name or (null)>` for a later `init`,
  `ProviderException: Could not construct MacSpi instance` for any other member.
- **Providers (l72,
  `probes/Prov`, `Attrs`, `Algs`).** `Security` answers the twelve a JDK installs, each an
  `InstalledProvider`, version `25` and the JDK's description words. Decided: a provider holds
  the subset's services as a JDK's legacy entries (`<type>.<algorithm>`,
  `Alg.Alias.<type>.<alias>` and the attributes a JDK gives), SUN the digests and
  `SecureRandom`s, SunJCE the Macs and factories, the other ten none; `getService` and
  `getServices` are read off the entries (the type as written, the algorithm or an alias
  whatever its case), so a provider of the program's own works the same way. A service's class
  is the subset's (`rontolisp.internal.Sha256`, `java.security.SecureRandom`,
  `javax.crypto.Mac`), not copied. `getInstance` follows the subset's tables, never the
  entries (a JDK honours a `put` or `clear` on SUN: `Prov.out`'s last lines). A provider of the
  program's own: no service is `no such algorithm: A for provider P`, a service is the JDK's
  `class configured for T (provider: P) cannot be found.` with the `ClassNotFoundException` of
  its class -- the subset makes no class from a name. **The entries are put when a member of
  the table first reads or writes it** (`Provider.entered`): a `Properties` with one `put` is
  96,708 B of Preview 1 where one that puts nothing is 48,462, so `getProvider().getName()`
  measured 136,772 / 140,873 / 157,173 put when made and 59,183 / 63,196 / 93,563 put late.
  The late `Mac` answers `getProvider` by choosing, a Mac of no provider `ProviderException`;
  a digest of the program's own answers null.
- **A DRBG's parameters (l72; `probes/Drbg`, `Drbg2`, `Case`, l18's `Reseed`).** A DRBG made
  with an `Instantiation` is `SecureRandom$Drbg` (a field in `SecureRandom` itself grew a
  `SecureRandom` alone by 17 / 94 / 90 B): strength -1 is 128, else the least of 112, 128, 192,
  256 no less than asked, past 256 `strength too big: N`; `PR_AND_RESEED` resists prediction,
  `NONE` and `RESEED_ONLY` are both `reseed_only` (as a JDK's default configuration has it). A
  draw or a reseed is held to them: `strength too high: N`, `pr not available`, `unknown params
  type:class X` (a draw; no space) / `unknown params type: class X` (a reseed); a generator but
  DRBG `UnsupportedOperationException`, after `params cannot be null` and a draw's null array
  (`NullPointerException`). Every bit is the host's; the personalization string is kept for
  `getParameters` (a new `Instantiation` each time), the additional input adds nothing.
  `getInstance` of parameters a generator does not take is `Error constructing implementation
  (algorithm: <standard name>, provider: SUN, class: java.security.SecureRandom)` -- the
  subset's class where a JDK names its own, decided not copied -- caused by `Unsupported
  params: class X` (SHA1PRNG too, whose JDK cause is a `NoSuchMethodException`). The class is
  written `"class " + getClass().getName()`: an object's class is no interface, and `+
  getClass()` reached `Class.toString`, 138,720 against 70,856 B for a DRBG made with
  parameters (`measure/D1.java`).
- **Not matched** (`doc/*/java/deviations.md`, "Digests and keys"): the classes of digests
  and derived keys (`rontolisp.internal.Sha256` where a JDK's is
  `MessageDigest$Delegate$CloneableDelegate`); `SecretKeySpec.hashCode` and a derived key's; a
  seeded SHA1PRNG never repeats a draw (every bit is the host's); a `NullPointerException`'s
  helpful message; the providers' differences above.
- **Pinned by** java-spec `a-digest-is-the-standards-function-of-its-message`,
  `a-mac-is-an-hmac-of-a-digest-under-a-secret-key` and
  `a-key-is-derived-by-pbkdf2-and-a-secure-random-draws-the-hosts-entropy` (the vectors of RFC
  1321, FIPS 180, RFC 2202, RFC 4231, RFC 6070, RFC 7914 and RFC 7677's exchange, then a JDK's
  lines, the providers' and a DRBG's among them; on all four);
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (a constant name
  holds one digest, a computed one all),
  `#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition` (`SslMacMD5`),
  `#theLoweringTable` (the constant table's `'(1 4294967294)`),
  `#theLibrarysSourceIsJavaAJdkCompiles` (the throws clauses); `IroncladNativeTest` (the
  kernels against the JDK's across the padding, MD2's and every rate's boundaries, keys longer
  than a block).
- **Not made**: `Security.addProvider` and the security properties, `Service.newInstance` and
  the engine classes but `MessageDigestSpi` (a provider of the program's own making a service
  of its class needs a class made from a name).

## java.util.Base64 (2026-11-22, l17)

**Invariant: an encoder writes RFC 4648's alphabet (section 4, or section 5's URL and
filename safe one) and RFC 2045's lines; a decoder answers what a JDK's answers for every
input -- the bytes, or the same refusal in the same words.** Theme: JDBC for PostgreSQL (the
SCRAM salt and proof, XA's MIME decoder). Java source; the singletons are static fields of
the nested `Encoder` and `Decoder`, so a program that only decodes makes no encoder.

- **How it was learned**: probes, then
  `compare.sh` (`Compare.java`, the library's file renamed `probe.B64` beside a JDK 25's: every
  text of `A g = - _ + / CR LF é` up to 7 characters under each decoder, `é あ` and a
  surrogate pair through `decode(String)`, random and corrupted texts, random bytes under every
  encoder): 75,464,477 comparisons, no difference. A mutant is caught; checking the harness so
  stays part of using it (reflection failing alike on both sides compared nothing).
- **What the answers showed** (the rules of `Base64.java`):
  - **The size a decoder asks of an array**, before reading (`Output byte array is too small
    for decoding all input bytes`, whatever the text holds): the basic and URL decoders take
    the padding from the last two characters, or count a partial group as padding, `3 * ceil(n
    / 4) - pads`, and refuse a text of one character (`Input byte[] should at least have 2
    bytes for base64 bytes`) first; a MIME decoder counts its alphabet's characters before the
    first `=` and asks nothing of a text shorter than two.
  - **Reading**: a character outside the alphabet is `Illegal base64 character <hex>` of the
    SIGNED byte (`-17` for `é` read as ISO 8859-1, `3f` past it); a MIME decoder skips it. `=`
    after no character of a quantum, or after two not followed at once by a second `=`, is
    `Input byte array has wrong 4-byte ending unit`; after one it ends the reading, and one
    character of a quantum left at the end is `Last unit does not have enough valid bits`; the
    bits a partial quantum leaves over are not checked (`Zh==` is `f`). After the padding
    nothing may follow (`Input byte array has incorrect ending byte at N`, N the character's
    position, one past it for MIME, which skips anything outside its alphabet there).
  - **The decoder's stream** reads no further than a read needs (`Under.java`) and stops at the
    padding; its refusals are `IOException`s in words of their own (`Illegal base64 character
    0x<hex>` of the unsigned byte, `Illegal base64 ending sequence:<24, 18 or 12>`); a refused
    read loses its bytes and the quantum under way, the stream reading on after the refused
    character.
  - **Encoding**: a MIME line length is rounded down to a multiple of four (below four, the
    basic encoder); the caller's separator array is kept, later changes changing the lines.
- **Sizes**: a program naming no Base64 is byte-identical (a JVM class carries its output
  path: compare at the same one); a decoder costs some 11 KB of wasm over what any program
  that can throw carries.
- **Pinned by** java-spec `base64-is-the-rfcs-alphabets-and-a-jdks-refusals` (RFC 4648 section
  10's vectors, then a JDK's lines for every encoder, 33 texts under each decoder, both
  streams); the harness is the artefact, not a test.

## java.net.URLEncoder and URLDecoder (2026-11-22, l11)

**Invariant: `encode` is the form HTML's forms send (`application/x-www-form-urlencoded`) of
the charset's bytes, `decode` its reading, and each refusal is a JDK's in its words.** Theme:
the Java shiori (`examples/java/shiori`): its links, flash cookie and `HX-Target`. Java
source over a charset's `getBytes` and `new String`, a run of escaped characters made bytes at
once.

- **Learned from a JDK's answers** (probes, then a harness, not kept, of 49,759,194
  comparisons: every text up to five characters over 16 -- `+ % ~ / é あ` and both halves of a
  surrogate pair among them -- and up to six over 12 for decoding, 600,000 random texts, five
  charsets; a mutant is caught). `%` with fewer than two characters after it is `URLDecoder:
  Incomplete trailing escape (%) pattern`; the first of the two that is no hexadecimal digit
  `URLDecoder: Illegal hex characters in escape (%) pattern - not a hexadecimal digit: "x" =
  120` (`%+1` and `%-1` too: JDK 25 reads no sign); an unknown or illegal charset name is
  `UnsupportedEncodingException` of the name, an empty one `URLDecoder: empty string enc
  parameter` for `decode` (which looks for the charset before it reads the text) and the
  empty name for `encode`; a null charset or name a `NullPointerException` (`charset`,
  `Charset`, `charsetName`).
- **Not copied: a JDK 25 leaves a lone high surrogate out** when it is the 7th, 14th, 21st ..
  character of a run it encodes together (`~~~~~~` and one is `%7E` six times, nothing for it;
  elsewhere `%3F`): the 300 comparisons that differ are all that. Here it is the charset's
  replacement wherever it stands -- and a surrogate with no partner is its three UTF-8 bytes
  anyway ("A string's elements are code points", `deviations.md`), so the corpus case holds
  none.
- **Pinned by** java-spec `a-form-s-text-is-encoded-and-decoded-as-html-sends-it` (UTF-8,
  ISO-8859-1 and US-ASCII, each decoder's replacements, the refusals in order).

## Numbers (2026-11-17, j72)

- **`long` is `(%mask-signed-field 64 x)`**, as the Clojure front end's unchecked verbs
  (`.kb/clojure-frontend.md`): both compilers fuse it with the operation under it into one
  wrapping `ladd`/`lmul`, `i64.add`/`i64.mul` (`.kb/jvm-int-fusion.md`,
  `.kb/wasm-int-fusion.md`), where a mask at 64 bits needs the bignum `2^63`, which declines
  fusion. **Every integral wrap is `(%mask-signed-field w x)`** (`JavaForms.wrap`), not the
  mask `(- (logand (+ x 2^31) 2^32-1) 2^31)`: two shifts where the mask is three operations
  over five-byte constants, on the interpreter one call where it is three (2,000,000 steps of
  `h = 31 * h + i % 7`: 2.5 -> 2.0-2.2 s), and over a lone leaf both compilers fuse it. A mask
  in a `let` or behind a test loses 4x (the j68 artefact README).
- **What the types prove reaches the backends as declarations.** A variable of an integral
  type is declared of its Common Lisp type where it is bound (`JavaForms.integerType`; a
  `char` `(integer 0 1114111)`, a string's elements being code points: `(unsigned-byte 16)`
  trapped on the first one past U+FFFF), several declarators a `let` each, since a `let*` gives
  its inner `let` every declaration. An operand whose form does not say its range -- a call, a
  field, a conditional -- is asserted with `the`, an `int` quotient as `(integer -2^31
  2^31)`: `MIN_VALUE / -1` is the one value past the type, which the wrap takes back. On
  wasm-GC that is a site with no fallback and a variable kept raw where it is read mostly raw
  (`.kb/declarations-type-checks.md`, "Declared integer ranges"); on the JVM a fused leaf cast
  with no fallback where nothing else bails (`Zone.class` 271,493 -> 260,065 B), and a local
  read mostly raw one `long` slot, a tree over such locals computed where it stands (l73:
  `.kb/jvm-int-fusion.md`, "Exact locals"); the interpreter reads none. A Common Lisp caller
  handing a method a value out of a parameter's range is a false declaration (on wasm a trap,
  on the JVM an error for a non-integer alone; `doc/*/java/index.md`). Measured 2026-11-20
  (wasm): ten `x = x + b;` over `int` parameters 2,098 -> 298 B, YAVI `Booking.java` 1,204,473
  -> 1,129,086; under the mask, kept raw by use 1,133,686 against every declared local raw
  1,136,369, none 1,143,837 or every declared parameter raw 1,143,772.
- **An array the types prove is declared of its vector or nil**: `(or null (simple-array E
  (*)))` (`JavaForms.arrayType`, E the element type a read answers), the other forms (a field,
  a call, a row of an array of arrays) asserted with `the` of the same; `.length` and the
  enhanced `for` take `(the (simple-array E (*)) (or a (%java-npe)))`. A null array's access
  evaluates its index (and a store its value) before the backends' check raises what
  `%java-exception-of` makes `NullPointerException` (`.kb/declarations-type-checks.md`,
  "Declaration-driven array emission"). The interpreter reads no array declaration; the wasm
  kinds and a general array's integer elements do (`.kb/wasm-int-fusion.md`, "Ranges, and sites
  with nothing to bail on"), and the JVM's fused reads and stores take the pinned arm
  (`.kb/jvm-int-fusion.md`, "Declared arrays"). Measured on wasm: a
  `long[1000]` summed 10^5 times 93.8 -> 1.44 s, 2,000,000 zone offsets 4.83 -> 2.59 s. **The
  premise (the dispatch's bytes) held only where nothing had pinned the array**: the type-test
  fold had already cut each undeclared site to the general arm, `make-array` inits made arrays
  general, and a general array's `length` at the speed levels is a 57 B arm where the call is
  3.
- **A quotient is an `i64.div_s` on wasm** for declared or asserted operands of `a / b` and
  `Math.floorDiv`, `MIN_VALUE / -1` wrapped by the mask (`.kb/wasm-int-fusion.md`, "Quotients
  of two integers"; the zone reader 12,765 -> 10,951 B); the JVM's `_iquo` is a `LDIV`.
- **`new T[n]` is a rank-1 `make-array` on wasm**, its size asserted `(the (integer 0
  2147483647) (%java-array-length n))` for the dimension parse (`.kb/array-literals.md`, "What
  a wasm `make-array` site costs"); a store takes a raw index into an array of any type:
  `SieveLoop` 8.9 -> 1.8 s (the JVM 2.9 s), the sieve bound not by memory but by the store's
  boxed index; 10^8 stores into a `String[1000]` 0.58 -> 0.41 s.
- **`new T[n][m]` is lowered at its site**, its lengths checked into variables declared
  `(integer 0 2147483647)` and each level but the last a `while` filling a vector
  (`JavaLowering.newArray`), not a call per row: 10^6 `new int[4][4]` 0.59 -> 0.29 s on wasm
  (by hand 0.27), the module 27,611 -> 18,313 B. Rare where measured: none in YAVI 0.18.0, one
  in the examples, none in the library.
- **`float` is the double of its value**, each result `(%single-float (op a b))`: rounding the
  binary64 result gives the binary32 one for `+ - * /` (the double holds every bit the
  rounding reads). **`%` of doubles** is `%java-double-rem`: the host's `rem`
  is the exact fmod on four backends (measured), but signals for a zero divisor and gives
  `0.0` for `-4.0 % 2`.
- **An array of a primitive type has the element type that holds its values**: `int[]`
  `(unsigned-byte 32)`, `byte[]` `(unsigned-byte 8)`, `short[]` and `char[]` `(unsigned-byte
  16)`, `float[]` `single-float`, `double[]` `double-float`, `boolean[]` `bit`; `long[]` and an
  array of references `T`. A store masks to the width, a read takes the low bits back signed.
  A packed `int[]` runs the JVM's `_iv` arms of `%aset`: `SieveLoop` 0.98 -> 0.48 s (wasm 0.37
  -> 0.27). `toCharArray` gives UTF-16 units; `new String(char[])` reads a surrogate pair as
  its character. An index outside it is `ArrayIndexOutOfBoundsException` with the language's
  message, read off the backends' `type-error` of `(integer 0 (length))`; a `catch` of
  `IndexOutOfBoundsException` or of the class itself takes Lisp errors.
- **The virtual call keeps the `cond`.** On `SieveTree` (2^18 leaves, 20 walks), JVM / wasm:
  the `cond` over the structure predicates 0.28 / 0.41 s, a generic function with three methods
  0.27 / 0.45, a per-class vector of functions in a slot 0.32 / 0.84.
- **`Math.fma`** is the exact `a * b + c` (`integer-decode-float`) rounded once, `scalb` the
  same with the exponent moved (clamped to +-2200: past it an infinity or a zero);
  `floatToRawIntBits` and `doubleToRawLongBits` are the plain bits: the value model has one
  NaN (`deviations.md`).

## The class of an array (2026-11-17, j81)

**Invariant: an array's vector tells the class of an array of `int`, `byte`, `char`, `float`,
`double`, `boolean` and `Object`; the class of any other -- of `long`, of `short`, of
references -- is what the run time recorded where its static type was widened, in a program
that can ask it; the store into an array is not checked.**

- **Recorded** (`JavaLowering.widened`) also where a DECLARED parameter erases otherwise
  (`List<long[]>.add`); the first record wins (the array met its exact type first).
  `Array.newInstance` records the class asked ("java.lang.reflect.Array and java.lang.invoke").
- **The gate** (`JavaClasses.arrayTypes`): a cast or `instanceof` reading a record, or
  `getClass()` on a type that can hold an untold array. Before it nothing records, so a program
  that never asks pays nothing and a `String[]` read as an `Object` prints as `Object[]`
  (`deviations.md`); a session always records. Not `toString()`: every program printing an
  object would record, and keep, every array it widens. `Arrays.deepToString` (Java over
  `instanceof`) arms it.
- **Measured** (2026-11-17, Lisp prototypes; JVM / wasm): the store check +9.5 / +31 ns a store,
  its gate never narrow (`Arrays.sort(String[])` passes an `S[]` as an `Object[]`, after which
  any store through an `Object[]` may be into an `S[]`): not made. A record 0.6 / 2.8 us, kept
  for the program's life (no backend has a weak table, wasm-GC no weak reference). A header slot
  of a displaced view 8x / 2.6x slower; one in the backends' own array header
  (`.kb/array-literals.md`) is the alternative left, unmeasured.
- **A cast is no inference context** (JLS 15.16): `(String[]) Arrays.copyOf(objects, n)` infers
  `T` from the arguments alone (`needsTarget`).

## A class as a value (2026-11-20, k30)

**Invariant: a class read as a value is ONE object a type -- `java.lang.Class`, Java source
of the library --, made before anything of the program runs and holding what the program
asks of one; a class compared with a class, or asked its name, is still its name and makes
no object.**

- **Made once, before the program** (`library/java/lang/Class.java`). Rejected: made at its
  first read -- two host threads of the JVM or the interpreter could make two, and identity is
  what `==` and a map read. It holds only what the program reaches of `Class`
  (`JavaClasses.reached`); a session holds all of it and keeps an earlier buffer's object.
- **`getClass()`** (`classOfFunction`) of a `StringBuffer` is the `StringBuilder`'s (its
  representation); `getName()` of a lambda's is a run-time error (a lambda is a function, which
  names no class).
- **The methods a class declares, by name** (`HttpServlet.doOptions` asks every servlet's:
  `.kb/servlet-httpserver.md`): the source's in its order, then the implicit ones of
  JLS 8.9.3 and 8.10.3; `""` for an array or a primitive type (a JDK's answer); refused for an
  intrinsic class or the library's own (its source is not the JDK's). A JDK 25 adds javac's own
  (`lambda$count$0`, a bridge, `$values`: `deviations.md`) and orders them otherwise
  (unspecified). `Method.declared` is package-private, so the library compiles under javac
  without a public constructor a JDK's lacks; `equals` and `hashCode` are a JDK's, `toString`
  refused (it needs the erased types and modifiers; asked more than names: "Reflection in full").
  One string of names a class (184 B), only where reached; rejected: a `Method` per name in the
  lowered code, 3,728 B of wasm for 29.
- **A class object initializes nothing; an enum's constants do**: what holds an enum's class
  object counts as calling its `values()` (`enumValues`), only in a program reaching
  `getEnumConstants`.
- **`==` of two parameterizations no value has both of** is the compiler's `incomparable
  types: Class<String> and Class<Integer>` (`distinctArguments`).
- **Not the language's** (`deviations.md`): reflection beyond "Annotations read where the
  program runs", "jmustache and reflection by name" and "Reflection in full"; `getInterfaces()`
  of an intrinsic class (what the program names); the class above a library class (this
  library's: `StringBuilder` under `Object`); `synchronized (Name.class)` holds the structure's
  name, not the object.

## Object.clone() and finalize() (2026-11-23, k16)

**Invariant: `Object.clone()` is a shallow copy (`copy-structure`; the identity number and the
monitor are side tables, so a copy is a new identity) of an instance whose class is
`Cloneable`, else `CloneNotSupportedException` of its binary name; a call reaching it through
a class that does not override it reaches an override below.**

- **The form** (`objectClone`) is told from the classes the program makes under the receiver's
  type (all `Cloneable`: `copy-structure`; none: the refusal; some: a test). Rejected:
  `instanceof Cloneable` and `getClass().getName()` where the program runs -- every program
  cloning would carry the interface test, and `getClass()` of an `Object` arms the array
  records. An interface has no `clone()` (JLS 9.2: `cannot find symbol`); `finalize()` does
  nothing.
- **Access (JLS 6.6.2)** (`protectedReach`), the receiver rule included:
  `clone() has protected access in Object`. The library trusts itself; protected fields and
  constructors are not held to the receiver rule (`deviations.md`).
- **An override** of no `final` method (`overridden method is final`; `getClass()` is not
  checked), with no weaker access (`attempting to assign weaker access privileges; was
  protected`), then the throws clause; `named` writes an inner class behind its classes, a
  static member class alone, as javac does.
- **The library's public `clone()`**, where a JDK 25 has one (asked by reflection), starts from
  `rontolisp.internal.Support.copy`, so a subclass's copy is of its class. As a JDK answers: a
  `HashMap`'s copy sizes its table as `putAll` does and puts the keys in walk order without
  `put` (a `LinkedHashMap` keeps its access order, asks for no eldest entry); a `Hashtable`'s is
  as long, each chain in its order; a `LinkedList`'s calls the copy's `add`; a `TreeSet`'s, of
  a view too, is over `new TreeMap<>(map)`; `Properties` shares its defaults.
  **Written qualified, not imported**: an import of `rontolisp.internal.Support` reordered the
  structures of every program naming an `ArrayList` or a `HashMap` (5 to 28 B of wasm either way).
- **`Locale` has `equals` and `hashCode`** (a JDK's hash is its own number): 622 B of wasm where
  a `Locale` keys an `Object` table. Every array is `Cloneable` and `Serializable`, its class
  object answering both in a JDK's order. **A `java.lang` class the library lacks** is refused
  by name (`JavaClasses.JAVA_LANG`: the 108 public types of a JDK 25's `java.lang`, asked of its
  run-time image).
- **`Serializable` of the intrinsic classes** (`JavaIntrinsics.LIBRARY.implemented`, added to a
  class only in a program naming `java.io.Serializable`, as `Comparable` and `CharSequence`
  are): `String`, `StringBuilder`, `StringBuffer`, `Number` (so every numeric box and
  `BigInteger`), `Boolean`, `Character`. Measured (2026-11-26): a program naming no
  `Serializable` is byte-identical on wasm and component (`.class` of a Java program differs
  run to run, 140,778 / 140,782 B from one jar); `instanceof Serializable` over strings and
  boxes costs 776 B of wasm in a 117 KB program (also holding `ArrayList` and `BigInteger`).
  **The library's Java-source classes declare it where a JDK 25 does** (2026-11-26; surveyed by
  a probe: `Serializable.class.isAssignableFrom` of each library class against the JDK's, the
  nested public classes through `getClasses()`, the factories' results through `instanceof` in
  one program run on the JDK and here): the roots (`Throwable`, `Enum`, `Class`, the collections,
  `Duration`, `Locale`, `Random`, `Pattern`, the atomics, the locks, the
  `Clock`s ...), the internal classes standing for a JDK's serializable one (`Collections`'s
  `Few*`, `SetFromMap`, `UnmodifiableCollection`; `ImmutableCollections`' `ListN`, `SetN`,
  `MapN`, `ListView`, `Entries`, `SequencedView`; `Synchronized`'s `Each`, `MapOf`;
  `TreeMap.View`, `ConcurrentSkipListMap.SubMap`), and the library's comparator lambdas, cast
  `(Comparator<T> & Serializable)` (free: `Comparator.comparing(..).reversed()` is
  byte-identical). **Measured**: a program naming no `Serializable` is byte-identical on wasm
  with `ArrayList`, `HashMap`, `TreeSet`, `ArrayDeque`, `LinkedList`, enums, exceptions,
  `Comparator`, `List.of`, `Collections.unmodifiableList`, `Arrays.asList`, `Set.of`, `Map.of`;
  `Collections.synchronizedList` costs 20 B; naming `Serializable` costs 688 B in a 182 KB
  program holding a dozen of the classes. **Where the library still differs** (each answers
  `true` where a JDK 25 answers `false`, from a view class extending a serializable one):
  `List.of(..).subList`, `Map.of(..).keySet()`, `TreeMap.keySet()`/`navigableKeySet()`,
  `ConcurrentSkipListMap.keySet()`.
- Pins: java-spec `an-object-is-copied-by-clone-where-its-class-is-cloneable` (`Serializable` of the
  intrinsics and of the library's classes), `the-library-s-collections-clone-what-they-hold`, the `refused:` cases `clone-*`,
  `an-override-of-clone-*` and `an-enum-*`, the reflection case's `int[]`; `JavaLoweringTest`
  (`Process`, the final method of a static member class, a weaker access);
  `java-http-server-spec.yaml`'s servlet case
  (`the-cookies-and-the-languages-of-a-request-and-a-cookie-of-the-response`, the API's
  `Cookie.clone()`).

## A locale of any language (2026-11-23, l08)

**Invariant: a `Locale` is any BCP 47 locale, made as a JDK 25 makes it. What it writes is
the root's (`ROOT`, a language a JDK has no data of), the English ones' (`ENGLISH`, `US`,
`UK`, `CANADA`), or refused by name where the program would need the locale's own CLDR
data. A program that makes no locale but the five pays for none of the others.**

- **The question** `rontolisp.internal.Locales.foreign()`: true where the program reaches a
  factory or a constant beyond the five (`Locale.made`), or code outside the library a `Locale`
  constructor (`JavaClasses.reachedOutside`). Rejected: a private constructor for the five, 8 B
  in every program naming `Locale`. Every member reads it in an `if` or `?:` condition, the
  only places `answered` folds it (`q() || x` cost `Locale.equals` 336 B, a helper 29 B).
- **Two fields**: a script, a variant and extensions are held in `country` after a NUL (three
  more fields: 237 B in every program naming `Locale`); `equals` and `hashCode` hold, the text
  being canonical. The read order ("Written qualified, not imported" above) made the field a
  `Map` and `Locales.refused` return `RuntimeException`.
- **Measured** (2026-11-23, wasm): programs of the five byte-identical,
  `examples/java/hello-servlet` among them; a first `forLanguageTag` +54,026 B. Kept out of the
  library (84 / 94 KB with them): `ArrayList`, `Collections.sort`, a 52-element array literal,
  `Integer.parseInt`, `String.split`, `StringBuilder.insert`.
- **The tag** (`Locale.parsed`, lenient for `forLanguageTag`, strict for `setLanguageTag`): as
  far as it is well-formed, with BCP 47's grandfathered tags and a JDK's old codes and
  compatibility locales (`iw` -> `he`, `ja_JP_JP` -> `u-ca-japanese`, `no_NO_NY` -> `nn-NO`).
  The factories answer one object per fields (interned, the five included), a constructor a new
  one. A comparison program ran the library's class beside a JDK's: 3,600,000 answers over twenty seeds, none differ;
  `probes/Tags.java`, `Made.java`, `Build.java` print identically.
- **Kind** (`Locales.kind`): -1 with a script, a variant, a Unicode keyword (not attributes nor
  other extensions: asked), `en` with a country but `US`, `GB`, `CA`, or a known language
  (`Locales.known()`: the 258 a JDK 25 writes otherwise than the root or lists as available,
  `probes/Known.java`); else the root's or an English one. An unknown language writes as the
  root; a week is the region's, never the language's (`probes/Regions.java`).
- **Refused** (`deviations.md`) as `<what> in the locale <tag> is not supported by the
  experimental Java subset yet`: a kind -1 locale's `%d %e %E %f %g %G %t %T` (any other's `%t`:
  "java.util.Formatter and the conversions a format lacked"); Turkish, Azerbaijani,
  Lithuanian upper-case conversions and case mappings whose SpecialCasing condition
  needs a combining class (`probes/Cases.java`); `localizedBy`'s `-u-nu-`, `-u-rg-`, `-u-tz-`
  and a `-u-ca-` calendar a JDK has (`iso8601` is ISO, any other `Unknown calendar system: `).
- **Bundles**: the default `Control`'s candidates of any locale, a JDK's (`probes/Bundles.java`;
  `_x` gives `en` twice).
- Pins: java-spec `standalone:` `a-locale-of-any-language` (a file of its own, so that the
  corpus keeps compiling the program of the five), java-http-server-spec's servlet
  `the-cookies-and-the-languages-of-a-request-and-a-cookie-of-the-response` (`Accept-Language`
  through `getLocale` and `getLocales`),
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (a refusal).

## Static initialization (2026-11-17)

The language initializes a class at its first use. Every initializer before `main` is silently
wrong once one prints or reads what `main` changed; a test at every use is right and costs each
read of an enum constant a call. So `JavaInitOrder` decides per class at the end of a pass,
from what the pass recorded (`Effects`), and the next pass is lowered with it:

- **EAGER**, initialized before `main` with no test anywhere, when the initialization with all
  it can call prints nothing, throws nothing (no `throw` statement), writes no static field of
  another class, and reads of another class only a `final` field whose type nothing can change
  (a primitive, a string, a box, a class of the program whose fields, with those of every class
  above and under it, are final and of such types); when every class it needs first is eager;
  and when no two need each other -- run in the order they need each other. Sound by
  induction: every reference such code can hold is made during the initialization, held by the
  class's own static fields, or immutable -- so nothing it reads can have been changed by code
  that ran first, and nothing it writes can be read before the class's first use. `final` is
  checked for this reason.
- **LAZY** otherwise: `|C.<clinit>|` runs once (its `%done` flag set first, the superclass's
  first), called by the class's static methods and instance makers and around a static field's
  read or write from a class not known to be initialized (itself, a subclass, a non-static
  class inside one). The class the program starts at is initialized by the entry form.
- Every static field is `(defvar |C.f| <default>)` (JLS 12.4), as is each `%done` flag, class
  object and interned string: a special without a value costs every read a test of the UNBOUND
  marker ([dynamic-special-variables.md](dynamic-special-variables.md), "A read of a special
  without a value"; -1,472 B of wasm in `examples/java/shop`, 2026-11-21). On the JVM a valued
  static is a raw-global candidate ([jvm-int-fusion.md](jvm-int-fusion.md), "Entry points
  beyond plain trees").
- **Where it runs**: what cannot throw, in top-level forms ahead of the entry; the eager
  initializers in `(defun %initialization ..)`, called ahead of `main` (an error in one is
  reported as what `main` left uncaught) and cut as any defun, where the entry form is one JVM
  method never cut: a static block of 3,000 statements stopped the compile
  ([jvm-method-size-limits.md](jvm-method-size-limits.md), "Bodies bounded by construction"). A
  session's buffer and a loaded library keep them in their `progn`.
- Classes that need no other run in the order of lowering (insertion-ordered maps): one source
  compiles to the same bytes every time (`JavaLoweringTest`).
- **An initialization that throws an object ends as the language ends it** ("A static
  initialization that throws", below).
- Not the language's: a run-time error inside an eager initialization surfaces before `main`,
  and one inside any initialization is neither wrapped in `ExceptionInInitializerError` nor
  remembered by the class (`deviations.md`, `.todo/k17`).

## Exceptions (2026-11-17, j73; the entry and the catch: j82)

- **What the run time raises is TEXT, `(error "~A" "java.lang.X: message")`** (`%java-raise`):
  a simple error whose format control is that text on four backends, no condition class of its
  own. Rejected: a `define-condition` `%java-error` -- its `typep` in the entry's handler cost
  every program ~30 KB of wasm.
- **ONE table from a condition to an exception** (`%java-exception-of`, `java.lisp`): a
  `division-by-zero` is `ArithmeticException: / by zero`, a `type-error` of `(integer 0 (n))`
  `ArrayIndexOutOfBoundsException: Index i out of bounds for length n`, one of datum NIL
  `NullPointerException`; the run time's text is its class, any other `RuntimeException` of its
  text (`JavaLoweringTest` checks `JavaLowering.RAISED` against `java.lisp`). **The same three
  host failures are read a second way, off the report TEXT** (`%java-host-report`), where no
  condition is held: `Division by zero`, `OP: The value NIL is not of type T`, `OP: The value I
  is not of type (INTEGER 0 (N))` ([error-handling.md](error-handling.md), "An uncaught
  condition reports ONE line"); java-spec `common-lisp-and-java-call-each-other` prints both
  readings on four backends.
- **Converted at the CATCH, not where raised**: only a clause above one of `RAISED` makes the
  program's object of it (`%lisp-error`), so a field read costs nothing. **Raising them at the
  site was measured and not made** (j82): an index test before every `aref` 9 ns an access on
  wasm (the host's check is an inline compare already), a zero test 2.6 ns a division, a null
  test free. Raised at the site where the host says nothing or the wrong thing (free): a null
  receiver of a direct call, an intrinsic or an array's length; a negative array length;
  `charAt` ("`charAt` is one read" below); a failed cast, `class A cannot be cast to class B`
  without the modules and loaders a JDK adds.
- **A `catch` is a `handler-case` clause of type `(satisfies %catch<n>)`**
  (`JavaLowering.catchTest`); a condition no clause takes passes on as it came, so the location
  line names where it was raised. Rejected: one clause on `error` ending in `(error c)` -- a
  computed datum, on which the condition narrowing bails
  ([error-handling.md](error-handling.md), "The condition floor"), so any `try` carried every
  condition class; a `handler-bind` exiting for a class it takes (+3.7 KB of wasm, +26 ns a
  `try` entered on both backends).
- **A `NullPointerException` the run time raises has no message**: a JDK's names the null
  expression by its class file's names (`"<local4>"` under the source launcher, `"p"` under
  `javac -g`), no one text to match.
- **Uncaught: the entry is `(%reporting-uncaught #'%java-uncaught-line (%host-exit 1) init.. start)`**
  ([error-handling.md](error-handling.md), "A program's own line"): `Exception in thread "main"
  <text>`, the location line (`  at File.java:4 in Uncaught.check(int,int)`: the interpreter
  and the JVM, wasm under `--report-locations`), the status 1 -- `System.exit` on the JVM,
  which ends a JVM running the class in process: hence no Java case in
  `UncaughtReportParityTest`, and `JavaSpecE2eTest`'s `standalone:` legs, each in a process,
  check the line by `at:`. The text is the table's, a host failure's read off its report, a
  `%java-throwable`'s `toString()` as it is (any text: hence the condition is passed, or nil).
  Read with `%string-search` alone (`%java-text-at`; `search` +10.6 KB of wasm, a character
  loop +11.5 KB). No handler holds the condition, so no signal site builds one ("Measured"
  below); a program starting threads keeps `%java-thread-main`'s `handler-case` ("Threads").
- **What nothing caught is reported with what it encloses**: the lines `printStackTrace()`
  writes (`\tSuppressed: `, `Caused by: `, `[CIRCULAR REFERENCE: ..]`; `Throwable.trace`), a
  JDK's without frames (`java-spec.yaml` `standalone:` with `stderr:`), then the location line
  -- only where an exception can enclose another (`JavaLowering.enclosing`; some 2 KB of
  wasm). The trace is one string concatenation over exceptions numbered by the trace that wrote
  them (`Throwable.traces`): a `StringBuilder` made three `try`/`catch` 140,544 B against 59,949,
  an array of the exceptions seen 3.6 KB against the numbers' 1.2 KB; two threads tracing at
  once read each other's numbers. `MatchException` has a private constructor of a message for
  `%lisp-error` (its API's takes a cause).
- **`try`-with-resources is written by the parser as JLS 14.20.3.1 spells it**
  (`JavaParser.withResources`; `try-with-resources not applicable to variable type`).
  Multi-catch: the parameter the lub, alternatives related by subclassing refused with javac's
  message.
- **`return`/`break`/`continue`/`yield` inside `finally`** exit out of an `unwind-protect`
  cleanup: CL's newer exit wins, Java's finally discarding what the `try` did (traps met on
  both compiled backends, `try { return a(); } finally { return 4; }` among them:
  [error-handling.md](error-handling.md), Phase 1).
- **A static initialization that throws** (JLS 12.4.2, a JDK 25's answers): a lazy class whose
  initialization can end with a thrown object (`JavaLowering.failingInitializations`) makes one
  that is no `Error` the cause of an `ExceptionInInitializerError`, lets an `Error` pass, and
  keeps IN PLACE OF ITS STATE the text a JDK gives the cause of the later `NoClassDefFoundError`
  (`Exception <toString()> [in thread "<name>"]`); any other class keeps the two-state test.
  **What the run time raises is not wrapped**: no object until a `catch` makes one, and a clause
  taking it would make every program with a lazy class hold its condition (40 KB of wasm); it
  passes as it came, the class as far as it got. **Marking the class unusable there was measured
  and not made** (j99, 2026-11-18, wasm): a `finally` setting a flag is free
  (18,744 -> 18,828 B), a later use throwing `NoClassDefFoundError` costs a program that throws
  nothing 31 KB. The text instead (no cause, the first error still not wrapped): `.todo/k17`.
- **`charAt` is one read** of `%string-code` ([string-index-cost.md](string-index-cost.md)),
  -1 outside the string (one call of `_str_code` on wasm), where the host's `char` checks
  nothing compiled (the JVM gave a `type-error` of datum NIL, wasm a garbage code). 26 letters
  read 10^6 times: 0.26 / 0.61 s (JVM / wasm), a bounds test through `length` and
  `_str_char_ref` 1.25 s on wasm; binding both operands cost the JVM 0.30 s (a variable read
  where it stands stays unboxed).
- **What a program that throws carries** (`Fib` 2,868 B of wasm; one `throw` in a loop, no
  `try`, 46,765 B): a NAME alone keeps a prelude function -- `describe` as `%java-throw`'s
  parameter kept `describe`, `type-of`, `pathname` (10.5 KB of wasm, 41 KB of class), the
  prelude keeping a function named in any position (`LispPreludeLibrary.process`);
  `JavaLoweringTest#theLoweringTable` holds `java.lisp` to binding no such name. 25 KB of the
  rest is the pipeline's, for a condition class with a `:report`
  ([error-handling.md](error-handling.md), "A condition's `:report` is what PRINTS it"): the
  print-object seam (`%pos-walk`, 8.8 KB), the printer (7.7 KB), the report through a string
  stream (8.5 KB) -- `.todo/k17`'s lead.
- **The run time's errors are the library's classes at every place a program can see one**
  (probed against a JDK 25 on four backends): a JDK's lines but the two stated messages;
  java-spec `a-lisp-error-reaches-the-catch-of-the-exception-it-is`.
- **Checked exceptions are checked** (JLS 11.2; every message, position and order asked of
  javac 25 over 136 probe programs, 132 alike):
  - **The clauses**: a type that is no `Throwable` is `incompatible types: String cannot be
    converted to Throwable`; an intrinsic names its classes (`Builder.throwing`), read only
    where its call is lowered. **What makes the check safe is
    `JavaLoweringTest#theLibrarysSourceIsJavaAJdkCompiles`**: the member a call reaches (own or
    inherited, by erased signature) declares what the running JDK's namesake throws, asked by
    reflection (over 2,000 members; 117 JDK overloads the library lacks are refused where
    called).
  - **Typing** (before any flow): `m() in C cannot override m() in B; overridden method does
    not throw X` (`cannot implement` under an interface's); `incompatible thrown types X in
    functional expression`.
  - **The flow** (`JavaFlow`, kind `THROWN`): a catch parameter not assigned in its clause HOLDS
    what the clause took; roots as JLS 11.2.3 (none for a static initialization, instance
    initializers with every constructor, an anonymous class's where it is made). A step of a
    session throws what it likes, as a jshell snippet does.
  - **What the answers showed that the specification does not say**: the position is a call's
    `(`, a creation's `new`, a `throw`, the body's `{` for an unwritten `super()`, the class's
    keyword for `in default constructor`, the resource's variable for `close()`, the `catch`,
    the multi-catch alternative; in one body the clauses' and lambdas' errors come where the walk
    meets them, the body's own at its end. `exception X has already been caught` is the flow's,
    for unchecked classes too. A clause whose exceptions were all taken before is a WARNING
    (`unreachable catch clause`, printed with `-Xlint:none` too).
  - **A type parameter in a `throws` clause** (JLS 18.1.3, 18.2.5, 18.4): unconstrained,
    `RuntimeException` where its bounds allow, else its bound; a lambda or method reference
    bounds it from below by what it throws, read from the parameter's DECLARED type (the
    inference's so far would fix it at the first lambda's exception). As javac, a catch
    parameter AROUND the lambda is its declared type there (`call(() -> { throw e; })` is
    `unreported exception Exception`); an anonymous class's initializer reads what it holds.
  - **A charset's name written as a constant** calls the member taking the `Charset`
    (`charsetsNamed`) but throws `UnsupportedEncodingException` as the one taking the name
    does, or a valid `catch` of it would be refused.
  - **Not as javac**: an error is one line (`error: File:line:col: message`), so a thrown type
    variable's lacks javac's `where` lines; a warning is printed whole (`JavaWarnings`).
- **Measured** (2026-11-17/18): a handler holding its condition makes every signal site build
  one (`.kb/error-handling.md`, "The routing gate"): `Fib` 41,656 B of wasm with it, 2,868
  without; a `try` in a loop 183,852 B against 64,019.
- **`getStackTrace()` answers an empty array, after `setStackTrace` too** (`deviations.md`).
  Rejected (j98): keeping what a program set, 275 B of wasm in every program that throws, for a
  set-then-get no program here writes. `AssertionError`, `ArrayStoreException`,
  `StackOverflowError`, `OutOfMemoryError`, `SecurityException`, `FileNotFoundException`,
  `InputMismatchException`: nothing of the run time raises them.

## Threads (2026-11-17, j76)

**Invariant: a thread is the host's where the target has threads (the interpreter, the JVM)
and a body suspended and resumed where the target has one stack (wasm, both targets): there
the lowering makes the functions a thread can wait in resumable, and a program whose threads
all wait is refused by name with the status 1, never gone on from.**

- **Where it sits.** `Thread` is Java source over `native` members, `java.lisp`'s "threads"
  section in three variants under the same names: the host's threads (`#-rontolisp-wasm`;
  `interrupt` a flag and a notify, no host interrupt), one stack with the program's own loop
  (Preview 1, `--native`), one stack with the host's events (`#+rontolisp-component`: "The
  host's events give the turns under a component" below). `wait` / `notify` throw from Java
  source, so a thread-free program's `catch (Exception e)` makes no new class.
- **Sixteen small programs are the measure** against a JDK 25's output: all
  16 on the interpreter, the JVM and, resumable, both wasm targets; 11 on one stack, a started
  thread run to its end on the stack of the thread that waits;
  `threads-wait-for-one-another-again-and-again` holds the rest.
- **A program that starts no thread has one** (wasm; `JavaLowering.startsThreads`: no call
  reaches `Thread.start()`): nothing resumable, `sleep` passes the time, `yield` does nothing,
  a `wait` with a limit sleeps, one with none is refused by name (`%java-stuck`); `Fib`, and a
  program reaching no member of `Thread`, stay byte-identical.
- **A monitor lives in a side table keyed by identity, not in a slot** (20,000,000 uncontended
  rounds, JVM / wasm): no lock 0.31 / 0.22 s, the table 1.28-1.52 / 3.3-3.5 s, a slot
  1.06-1.21 / 2.4-2.6 s; but a slot costs every object (a third one, 50,000,000 never locked:
  3.6-3.7 against 3.2-3.6 s on the JVM, 18.9-19.5 against 16.8-17.1 s, 44 ns an object, on
  wasm) and cannot hold the monitor of an array, a string or a `new Object()`. A thread
  keeps the monitor it entered last, so a lock taken again and again needs no look. Past 4,096
  monitors the table drops one at its last exit when nothing waits in it, so an object locked
  once is not kept for ever (a bound of 256 cost 1,000 objects locked in turn 337 against 135
  ns a round on the JVM); a weak identity table in the backends would retire the bound. The
  size is a global (`%java-monitors-held`): asking the table at every last exit was 30 of the
  170 ns an entry and its exit take on wasm (2026-11-19: 1.65 s with the monitor, 0.26 s
  without, 1.33 s with no size asked); the rest is some twenty `aref` / `%aset` of general
  vectors (`.todo/k20`).
- **A program that cannot tell a monitor held from one that is not holds none**: until a pass
  reaches a member of `Thread` (`JavaClasses.monitors`) `synchronized` is the null test and
  `volatile` any field (`synchronized` methods, no thread: 64,060 / 42,587 B of class / wasm,
  64,015 / 42,581 without, 70,820 / 56,280 holding monitors).
- **`volatile` is an access under one lock** (`%java-volatile`), and the atomics' methods are
  `synchronized`: the JVM backend's globals and slots are plain fields a JIT may read once for
  a loop, and a lock is what the pipeline has to order two accesses.
- **Static initialization in a program that starts threads is the class's own**
  (`%java-init-busy`): a thread asking for a class another initializes waits, the initializing
  one goes on, an initializer that throws still ends the class; on wasm the wait is a
  suspension (`%java-suspending-init`) in an initializer that can itself suspend, the only kind
  another thread can find under way, and any other refuses by name what it did not foresee.
  Rejected: one lock for every class -- an initializer joining a thread that initialized
  another class never ended on the interpreter and the JVM (the corpus's `Loud`).
- **The entry of a program that starts threads** is `%java-thread-main`, which reports what
  `main` leaves uncaught (`%java-thread-ending` with a shutdown hook or a file to delete:
  "deleteOnExit and shutdown hooks", under Files); a thread's body reports in a
  `handler-case`. **The two handlers hold their condition, measured and kept** (2026-11-18):
  with neither, a counter under four threads is 92,394 -> 86,755 B of wasm and 127,038 ->
  120,357 B of class (6%, 5%), 92,850 -> 89,597 B with a `catch` of its own (3.5%); not the
  handlers (0.5 KB) nor the text they read (0.4 KB) but the held condition, which every signal
  site builds. Not holding it needs `%reporting-uncaught` as a form inside a program, where a
  thread ends and the program goes on: a new form on four backends for 3.5 to 6% of a program
  that starts threads.
- **`System.exit` on a started thread** ends the program, the compiled backends at the call;
  the interpreter's exit ends only its own thread (`eval/LispExitSignal`), so the thread the
  program started on is woken to exit, not left in `join`
  (`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot`).
- **Measured** (2026-11-17, ns a round in a program reaching `Thread`, javac's class / this
  JVM class / wasm): `synchronized` over one object 19 / 50-60 / 90; a `volatile` read and
  write 6 / 24 / 1. Reaching no member of `Thread`, a loop costs what it does without the
  keyword; `Fib`, `WrapInt`, `Sieve`, `WrapLong` are byte-identical.
- **Stated in `deviations.md`**: a `.wasm`'s turns (a loop that never waits and reads nothing
  another thread writes keeps its turn); what is shared with no monitor is the host's;
  `WAITING` while a notified thread retakes its monitor; no handlers asked for `main`'s
  thread; an accumulator's one value; our thread numbers; a class literal as a `synchronized`
  lock ("A class as a value").
- **`getState()` answers `BLOCKED`** (`%java-thread-blocked`): on the host by a mark
  `%java-monitor-enter` sets ONLY when it found the monitor another thread's (set at every
  entry, a thread running `synchronized` blocks would read `BLOCKED` for two stores' time); on
  one stack when the thread is among those the monitor's last exit lets go on. The owner is
  read with no lock: a state is a moment's. Pinned by the corpus's `Turns` (`blocked 2`: a
  thread polls another's state while holding the monitor that one enters).
- **Pinned by** `java-spec.yaml`'s `standalone:` thread cases (so that the corpus program
  starts no thread and keeps the thread-free lowering),
  `JavaSpecE2eTest#aProgramWhoseThreadsAllWaitIsRefusedWhereThreadsTakeTurns` (the two wasm
  legs), `JavaLoweringTest#aMonitorIsEnteredAndLeftAndAThreadIsTheRunTimes` (the host's forms,
  then the resumable ones) and `#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`.

### In the browser (2026-11-19, k10)

**Invariant: the browser's interpreter starts no Java thread. A whole program that can
start one is handed by `PlaygroundRepl.run` to its `OneStack` -- the page compiles it as
`rontolisp Main.java -o main.wasm` does and runs the module in the engine the image itself
runs on, where its threads take turns ("Resumable bodies" below) -- and a buffer of the
Java session refuses `start()` in words.**

- **The cause.** Web Image has one thread and no stack to switch to: a body runs to its end
  inside `make-thread` (`Target_AsyncRuntime.spawnThread`, [threads.md](threads.md)), so a
  thread that waits, as a pool's worker does for a task, waits for ever. Measured on the
  interpreter (2026-11-19, GraalVM 25.0.5): the pool example of `doc/*/java/index.md` and the
  HTTP client example froze the page; the counter (threads that never wait) printed.
- **The seam**: `PlaygroundRepl.run` hands a program over when `JavaLibrary.startsThreads`
  (its forms name `%java-thread-start`) and the host gave it a `OneStack`, which only the
  `web` bootstrap does (`RontoPlayground.runCompiled`; a JVM host keeps host threads). A Java
  session step runs inside `LispEvaluator.refuseThreads(reason)`: `Exception
  java.lang.RuntimeException: a thread is started only by a whole program in the browser ..`;
  the other picks keep `make-thread`. "Run source" calls `rontoRunProgram` for the Java pick:
  a session only DECLARES a `main`.
- **The module's host** is `BrowserModule`, in the image's JavaScript so that every host of
  the image has it, refusing an import it has not by name before anything runs; standard
  error is on the page (`PlaygroundRepl.page()`).
- **Weighed and not taken**: the interpreter lowering as the wasm backends do -- its
  `async-defun` is a virtual thread a body, so on one thread an `await` of a future nothing
  settled cannot return to the scheduler without a second, resumable evaluator or a
  continuation-passing pass over the core forms (what `WasmAsyncEmit` is to the backend);
  JSPI -- the glue between a promising export and a suspending import is Web Image's own,
  every per-thread state of the evaluator would be swapped at each switch, and not every
  browser has it; one stack -- 11 of the 16 programs.
- **Measured** (2026-11-19, the pool example, a module byte-identical to the CLI's): 4.5 s to
  the output in node, 3.4 to 6.1 s in Chrome, nearly all the compile, against 0.25 s for an
  interpreted hello; a program that starts no thread pays one walk of its forms; the image
  +0.6 to 1.2%.
- **What does not run there**, and says why: a program that starts threads and sends an HTTP
  request (no transport in a Preview 1 module: the compile's refusal, under a line saying why
  the program was compiled; the doc's client example is one, `sendAsync` reaching a pool:
  `.todo/k22`), a server (the interpreter's `IOException`).
- **Pinned by** `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram`
  (the hand-off, the session's refusal, the other picks' threads; a JVM cannot run the
  image's route) and `web/playground-check.js` (the image from node: the pool example, a sleep
  and a join with standard input, an uncaught exception, the refusals, a compiled macro;
  `pages.yaml` runs it before publishing, `./mvnw test` does not).

## java.util.concurrent (2026-11-17, j83)

**Invariant: everything of `java.util.concurrent` is Java source over the monitors of
"Threads" -- no primitive, no backend change, no branch for a target: a thread waits on wasm
as it does on a host.**

- **What is there**: `doc/en/java/classes.md`; the atomics, the adders and the accumulators
  are `Number`s ("The decisions"); still missing: `.todo/k21`. Each member's behaviour is a
  JDK 25's answer to a probe run on four backends.
  `AtomicMarkableReference` and `AtomicStampedReference` (l86; probes): every
  member under the object's monitor, as `AtomicReference`'s; the reference compared by
  identity; a weak compare-and-set fails only where the strong one does (a JDK's may fail
  spuriously, which no probe shows). Pinned: `threads-share-concurrent-collections-and-completable-futures`
  (`setsDequesAndCounters`: four threads' 2,000 compare-and-sets of a stamp).
- **The classes a JDK synchronizes hold their monitor**, `StringBuffer` too
  (`JavaIntrinsics.guarded`), a thread-free program paying the overrides alone (`Sb`: +932 B
  of class, +227 B of wasm); javac refuses `Map.super.m()` in `Hashtable`, whose superclass
  already has the interface (`theLibrarysSourceIsJavaAJdkCompiles`).
- **A field updater holds the NAME of its field** (`JavaLowering.fieldUpdater`, where the call
  is lowered), not a number counted there, which a session's next buffer would not find (the
  session test's `up`). **A swap is ONE volatile access** (`%field-swap`), atomic beside a
  plain write: an updater synchronizing on itself or on the object would lose a write made
  between its read and its store. Rejected: accessors as lambdas held by the updater -- a
  `funcall` of a value the resumable analysis knows no interface of reaches every lambda of the
  program (`JavaResumable`, "FUNCALL"). A swap compares as `==` does, so `compareAndSet(o,
  1000, 5)` on an `Integer` field succeeds (two boxes of one value are one object) where a
  JDK's two boxes differ (`Updaters3.java`). **What `newUpdater` refuses is a JDK 25's, in its
  order, thrown where the call runs** (`java.util.concurrent.atomic.Fields`; 49 calls asked):
  no field of the name in the class itself (`RuntimeException` holding a
  `NoSuchFieldException`), another type (`Must be integer type`, `Must be long type`; a
  reference updater's `ClassCastException`), not `volatile` (`Must be volatile type`),
  `static` (`IllegalArgumentException`, no message); a wrong object or value is a
  `ClassCastException` with no message, a JDK's answer to a raw type. Refused by name when
  lowered, where a JDK throws when it runs: a name that is no constant, anything but class
  literals, a field the caller cannot read. Class names are a JDK's
  (`AtomicIntegerFieldUpdater$AtomicIntegerFieldUpdaterImpl`).
- **`invokeAll` with a limit hands a task over while time is left** (asked of a JDK): nothing
  runs for a limit of zero or less, and what is open when the limit or an interrupt ends the
  wait is cancelled with its thread interrupted. `Thread.join(Duration)` of zero or less, or
  of an ended thread, leaves an interrupt for the next wait; of a thread not started it is
  `IllegalThreadStateException: Thread not started`.
- **A `StampedLock`'s stamps are a JDK 25's numbers, read from its answers** (the 127th reader
  on is told 382), and so are its conversions, its interruptible members' answers and its
  views' names. Not a JDK's: no thread is preferred, so readers arriving for ever keep a writer
  out.
- **A `Phaser`'s tree is counted under its root's monitor.** Measured against a JDK:
  `arriveAndAwaitAdvance` answers the thread whose arrival ended the phase the NEXT phase's
  number even when the tree ended -- which thread that is moves with the load, so the corpus
  masks the bit and asks it of a phaser of one party; `getPhase()` of an ended tree is the next
  phase with the sign bit, of a forced end the current one. The documentation's reading, not
  asked of a JDK: a party registering when every other has arrived waits for the next phase.
- **A pool's hooks cost only a program that declares one** (`JavaEmitter.Context.declaresBelow`;
  `afterExecute` needs a catch of every exception around each task): a fixed pool running four
  tasks is 226,375 B of wasm / 278,314 B of class with no hook, 239,533 / 317,014 with an
  empty `afterExecute`. `terminated()` runs outside the monitor on the thread that finds the
  pool shut down and its last worker gone (a JDK's order, probed).
- **A callback of the library is an interface of its own, never `Runnable`**: a call through
  an interface reaches any class or lambda of it, so `Timetable`'s end as a `Runnable` reached
  every task and took a scheduled pool running one task from 250,061 to 348,553 B of wasm; as
  `Timetable.End`, 252,774.
- **A stage is made by `newIncompleteFuture()`** (k01), so a subclass decides what its stages
  are; what `copy()`, `minimalCompletionStage()` and `failedStage` answer was asked of a JDK.
  Not a JDK's: `defaultExecutor()` answers `Common.pool()`, not `ForkJoinPool.commonPool()`'s.
- **The pools of one thread are behind a wrapper** named as a JDK's: `instanceof
  ThreadPoolExecutor` is false, as on a JDK.
- **A task at its time is `rontolisp.internal.Timetable`** (a scheduled pool's, a `Timer`'s).
  A JDK's pool of two idle threads gave sequential tasks to its threads in turn when quiet and
  one thread two in a row under load, so no test asks which thread runs a task. **A thread of
  the table reads the clock ONCE a round**, for whether the soonest task is due and how long to
  wait: read twice, a task whose time came between the readings was neither taken nor waited
  for (a pause of 0 to -2 ms is `wait(0)`, below that a negative `wait` throws) and the program
  never ended, on every backend -- the "lost wake-up" of
  `a-scheduled-pool-and-a-timer-run-tasks-at-their-times` on the interpreter, not the park,
  monitor or condition-variable path (13 and 26 of 96 copies left waiting, 0 of 384 with one
  reading).
  Every other timed wait in the library tests `left <= 0` on the reading it waits by.
  `JavaSpecE2eTest#aTaskWhoseTimeComesBetweenTwoReadingsOfTheClockRuns` forces it: the
  interpreter's `%nano-time` answers a millisecond later at every reading.
- **A pool is a core size, a most size, a queue and a handler**, as a JDK's; the queue is the
  program's own object, used under the pool's monitor the workers wait on, so a task put into
  `getQueue()` waits for the next `execute`. The refusals' messages are a JDK 25's
  (`corePoolSize must be non-negative` ..).
- **What depends on a `CompletableFuture` runs in the order a JDK runs it, measured**: of the
  future set first, the dependent added LAST first; of a future one of those sets, right after
  it, the one added FIRST first (`d3 e3a d2 e2a e2b g1 g2 e2c d1 e1a e1b`); a stage of two
  futures is one dependent added to both where the stage is asked for (`q1 all combine2
  combine1`).
- **`ForkJoinPool` is a thin layer over a `ThreadPoolExecutor`** that grows a daemon thread
  whenever none is free, and the joining thread runs a task no thread took, so `left.fork();
  right.compute() + left.join()` needs no second thread. Not a JDK's: the parallelism is a
  number answered (2, 1 for the common pool: there is no `Runtime`), no work is stolen, and an
  exception is thrown again as it is where a JDK, when another thread ran the task, throws a
  new one holding it -- made by reflection, which the subset has none of.
- **A member that never waits calls nothing that can** (a rule of the library's source:
  `offer(e)`, `poll()`, `tryLock()`, `add` have bodies of their own, `Bounded.insertNow` beside
  `insert`; a program's callback, as `CyclicBarrier`'s action, runs with the monitor given
  up). A `synchronized` region is held across a suspension once a call in it can REACH a wait,
  and through an interface one such member makes every region calling it one: sharing a body
  with the waiting member put 30 regions of the corpus's `Handing` in that set and the
  functions to lower twice from 18 to 49% ("A thread suspended and resumed" below). Pinned by
  `JavaLoweringTest#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`.
- **What the concurrent collections cost in place of `synchronized`** (2026-11-22,
  `examples/java/shop`'s `Ledger`, 325,507 B as a component): `AtomicLong` +1,126 B,
  `CopyOnWriteArrayList` +7,556, `ConcurrentHashMap` +23,083, mostly resume bodies (it extends
  `AbstractMap`, whose `equals` loops, so every `equals` it reaches turned resumable); all
  three +10.0%, 335,071 B once a loop around a map's `put` takes no turn ("What made functions
  resumable that cannot suspend"); a `ConcurrentLinkedQueue` for the list no cheaper. A
  servlet program holds the container's map already: there a `CopyOnWriteArrayList` costs the
  most (`.kb/servlet-httpserver.md`, the guestbook: +66 KB).
- **A lock is a monitor's protocol, not a monitor**: each thread waiting on a `Condition` has a
  ticket of its own, so `signal` wakes the longest waiter and is not lost between the lock
  given up and the wait; a read-write lock's `tryLock()` goes ahead of the waiting writers, as
  a JDK's does.
- **A pool's workers are named as a JDK's** (`pool-3-thread-2` in the corpus replaces a worker
  a task's exception ended). **A worker that ends leaves `workers` in the monitor hold that
  decides it** (k54): leaving later, an `execute` in between counts it, queues the task and
  wakes nobody, and the task never runs -- 300 tasks in a row on a pool of no core thread
  stranded 76 on the interpreter and 70 on the JVM, 0 on both wasm targets (whose threads take
  turns) and under the JDK. `locks-latches-queues-and-executors-hand-work-between-threads`
  runs 200 such tasks on two pools.
- **`ConcurrentHashMap` keeps the order a JDK 25 walks it in, derived from outputs alone**
  (rule 2 of the provenance file: no source was read; probes and differential runs: of 80 seeded runs of 300
  random operations, 52 print the JDK's walk at every step, the other 28 first depart where a
  chain holds nine keys or more in a table of 64 or more; 17,396 steps identical). The rules
  are the class's header; unlike a `HashMap`, a new key goes to the END of its chain and the
  table doubles when the count REACHES three quarters (12, not 13). Not kept, as for a
  `HashMap`: a chain of more than eight keys in a table of 64 or more (a JDK's tree, whose
  order after a removal depends on its shape).
- **What a program can tell of a JDK's messages, measured**: a cancelled `CompletableFuture`'s
  `join` / `get` throw a NEW `CancellationException`, the member's name its message and the
  cancelling its cause; a stage's own exception and one taken over are held in a
  `CompletionException`, one given to `completeExceptionally` as it is; `allOf` fails as the
  first given that failed; what a handler throws is `Exception: <class> thrown from the
  UncaughtExceptionHandler in thread "<name>"` after an empty line.
- **A program that sets no handler holds no catch of every exception** (`Thread.body` calls the
  `Guarding` interface, whose one class the setters make: "Lowered as far as the program
  reaches"); `InheritableThreadLocal` likewise.
- **A library enum needs `java.lang.Class`**: javac's `valueOf` of an enum calls
  `Enum.valueOf(Class, String)`, which the library's compile under a JDK needs, and this front
  end types every signature it reads ("A class as a value").
- **A compact source file brings the three packages** (`LIBRARY_PACKAGES`).

### A thread suspended and resumed: the two leads measured (2026-11-17, j83)

The second lead is made ("Resumable bodies" below; its first step, "The regions a suspended
thread can hold"); the first waits for wasmtime: re-measure its three conditions, and the
component threads' one, on each wasmtime the project moves to.

- **Wasm stack switching cannot be used** (wasmtime 49.0.0, and 51.0.0 on 2026-11-22, a WAT probe).
  The three conditions: off by default (`-W stack-switching=y`); x86-64 Linux and macOS only,
  with the compiler's inlining off (aarch64 and Pulley unsupported; the native outputs target
  `macos-aarch64`); A COLLECTION WHILE A CONTINUATION IS SUSPENDED ABORTS THE HOST up to 49.0.7
  and holds from 50.0.0. An exception thrown in a continuation reaches the `try_table` around
  its `resume`.
- **The component model's threads pass the collection, not the default** (x86-64; wasmtime
  49.0.0, and 51.0.0 on 2026-11-22; the probe, a WAT file,
  which wasm-tools 1.263 parses only under `WAST_STRICT_COMPONENT_INDICES=0`). Under `-W
  component-model-threading=y` a component's threads are fibers of the host, the switch
  `thread.suspend-then-resume` (`suspend-then-promote` traps `cannot block a synchronous task
  before returning`); A COLLECTION WHILE A THREAD IS SUSPENDED HOLDS under each collector; a
  switch is 0.55 us, a `cont` switch 0.1 us, the futures' hand-over 2.2 us. Not made: without
  the flag the component is refused at load, so `wasmtime run` / `serve` could not run a
  `--component` output; a `--native` output is a core module and keeps the resumable lowering
  anyway; aarch64 unmeasured. Re-measure the flag's default alone: then a component's thread
  can be a host thread, its scheduler a switch, and no function of a component is lowered
  twice.
- **Resumable bodies: how much of a program would be lowered twice**, counting the functions
  that can reach a point where a thread is suspended (`--dump-ir`, five programs). A monitor's
  entry has to count: a thread suspended in `wait` still holds every OTHER monitor it entered.
  With it, a program over the concurrent library has 40 to 47% of its functions resumable;
  without, 2 to 16%. What decides it is whether a `synchronized` region can be held across a
  suspension, which the closed program answers per region (no call in it reaches one -- every
  atomic, every `ConcurrentHashMap` member).

### The regions a suspended thread can hold (2026-11-18, j89)

**The analysis brings the functions to lower twice back to the ones that reach a wait, a
sleep or a yield, in six programs of seven; the seventh is decided by how well a function
value is typed, which the IR does not tell and the lowering does.** The lowering was
therefore worth making ("Resumable bodies" below).

- **Measured** (2026-11-18, `held/held.py` over `--dump-ir`: a region is held when its body
  reaches a sleep, a yield, an entry that may block or a wait on another lock; a bounded
  buffer, `examples/java/shop` and the corpus's standalone thread programs): no region is held
  but in `Sharing` (0 .. 55 of 168). Resumable, from the unsound to the sound reading of a
  call through a function value (not followed .. every lambda of its arity): where the two
  meet, 1.5 to 18% of a program's functions, against 9 to 55% when every monitor's entry
  counts; `Sharing` 42 .. 425 of 853.
- **`Sharing` is not answered by the IR.** Its 425 come from one place: a
  `CompletableFuture`'s dependents are lambdas called through a local or a field, in the IR a
  `FUNCALL` of an unknown operand reaching every lambda of one parameter, a thread factory's
  among them. The lowering knows the interface of every lambda and every such call, so the
  analysis belongs in `JavaLowering`, beside `Effects`; interface precision is not enough for
  `Sharing`: the lambdas handed to each parameter are needed (`flow`: 34 of 492; "Resumable
  bodies").
- **The component runs a suspended body** (by hand, `async/`): a bounded buffer of two
  `async-defun` bodies, an `unwind-protect` and a `handler-case` across the awaits, prints
  what the interpreter and the JVM print. A resumable call that does not suspend costs 35 ns
  on Preview 1 (a settled future made and read) and 90 ns on the component (the frame). A
  thread's wait needs a future the PROGRAM settles: the component's `%future-new` is one, and
  Preview 1 runs the component's machines for a module naming it
  ([async-await.md](async-await.md)). Trap: `(await (wait-for 0))` is no yield -- on the
  component a timer of 0 ms is settled when made and the await never suspends, so a loop
  polling with it never lets another body run: the interpreter and the JVM end, the component
  does not (`async/spin2.lisp`).

### Resumable bodies (2026-11-18, k00)

**Invariant: on wasm a program that starts threads is lowered with every function a thread
can be suspended in as an `async-defun` and every call of one as an `await` (`JavaResumable`,
where a wasm target reaches `Thread.start()` or the HTTP server); a thread that cannot go on
awaits a future of its own, and the thread the program started on settles the futures of the
threads that can, the one waiting longest first (`java.lisp`'s `#+rontolisp-wasm` "threads").**
The interpreter and the JVM keep host threads. Preview 1 runs `WasmAsyncEmit`'s frames,
`asyncMode` being on for a module naming `%future-new` ([async-await.md](async-await.md),
"Preview 1").

- **The analysis**, a fixed point over the calls (`JavaResumable.Census` is what a test and
  these notes read; `-Drontolisp.java.resumable=FILE`, a switch of the JVM running the
  compiler, lists each resumable function with its first reason, `-Drontolisp.java.flows=REGEX`
  what values may be): a function is resumable when it reaches a SUSPENSION -- a sleep, a
  yield, a wait, a loop's turn, the entry of a monitor whose lock may be a region's held across
  a suspension ("The regions a suspended thread can hold"; `Lock.mayBe`: a `final` field made
  by `new C()` holds exactly a `C`, which no other such field holds, so the `new Object()` a
  program sleeps under is no library class's `this`). What the listing showed of a library
  written without it: [webmvc-fn-lite.md](webmvc-fn-lite.md), "Written for the compiler".
- **A function value reaches only the lambdas handed to the parameter it is** ("What a receiver
  may be"). Reaching every lambda of its interface, `Sharing` had 395 resumable of 853 and 49
  regions held (one resumable `Function` made `ConcurrentHashMap.compute`'s region held, and so
  every member of the map and every `equals` resumable), against 103 and none.
- **A loop's turn.** Threads take turns only where they suspend, so a loop only another thread
  ends would turn for ever (2 of the 16 programs) and one that never ends would hold the others
  (the corpus's daemon `while (true) counter.incrementAndGet()`). A loop outside every region
  gives a thread that can go on a turn once in 1,024 (`%java-suspending-turn`; not while its
  thread holds a monitor, so a turn holds no region) where another thread's write can end it or
  keep it going (`JavaResumable.turning`): never when its own progress ends it (`bounded`: a
  conjunct of its test asks for more of an iterator its body takes from, or keeps below a bound
  a counter only the loop raises by a constant step); in the front end's library only where its
  test reaches, through any call, a synchronization action the program wrote (its `volatile`s
  and monitors keep its own state, and where it waits it waits: "A member that never waits
  calls nothing that can"); else where its test reaches one (a `volatile` access, a monitor's
  entry, the interrupt's test) through any call, or its body by name and the loop does not
  decide its end (`decided`: its test and exits read only locals and pure operations
  (`Node.pure`), the locals assigned in the loop only from such values, outside every region and
  under such tests, and the test or an exit reads one the loop assigns). A class's
  initialization runs once: no synchronization action. Nor is the making of what a `throw`
  throws (`throwing`): the throw goes whatever it reads, so the log writer an `SQLException`'s
  constructor reads gave every JDBC row loop a turn and its chain up to the servlet
  container's dispatch resume bodies (`jdbc-spin-postgres.md`, "Compiled by rontolisp"; the
  shiori 150 -> 148 resumable, 4,046,877 -> 4,036,565 B; guestbook, mvcfn, httpbin, shop
  byte-identical; pinned by `Census.rowed` in
  `JavaLoweringTest#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`). The loops that
  keep their turn: `deviations.md`.
- **The forms** are as `JavaResumable`'s comment states; a function the run time calls (a
  `#'function`, a lambda the lowering wrote) is called where nothing awaits, so a resumable one
  is wrapped in `%java-now`: its value if it ended at once, else a refusal by name (a
  `synchronized toString()` under `String.format` works while no thread holds the monitor).
- **The scheduler** (Lisp; `start()` returns before the new thread runs) gives turns at the
  program's end while a thread that is no daemon lives, sleeping to the soonest time; with none
  that can go on and no time the program is stuck: `%java-stuck`, a message and the status 1,
  where a JDK waits for ever. A monitor or a class the analysis called free that a suspended
  thread holds is `%java-unforeseen`, never gone on from.
- **Measured** (2026-11-18, wasmtime 49.0.0, Preview 1, one stack -> resumable): `Handing`
  417,378 -> 711,585 B, 113 of 578 resumable (the +71% its `main`, 85 KB as a resume body),
  114 -> 140 ms; a program starting no thread byte-identical. A call that does not suspend costs
  88 ns resumable, a hand-over by `wait` / `notify` 2.2 us (400,000 in 0.9 s, a JDK 25 4.9 s).

### What k07 measured and made of the resumable lowering (2026-11-19)

- **A loop whose one suspension is its turn is a function of its own** (`JavaResumable.turns`):
  a `lambda` runs the innermost such `while` until its slice is spent, a `while` around it
  awaits `%java-suspending-turn`, and an exit of a block around the loop is answered as a
  number and a value. On a synchronized counter under four threads (0.95 s on one stack) a
  resume body took 2.50 s, every plain shape 1.55-1.67 s, a lambda that never turns (the bound)
  1.65 s: a resume body keeps no number unboxed, and the
  backend's fusions kept between suspend sites could give no more than the bound, so the
  simplest shape: 1.43-1.65 s, 1.8 KB a loop (`Monitors`: lambdas +14.3 KB, resume bodies
  -2.7 KB). The rest of the counter's 1.5 s is the monitor.
- **What a suspend site costs: 250 B and 12 B for each local alive across it**: `Handing`'s
  `main`, 82 awaits and some 74 locals, is 93,005 B, about 73 KB of it spills. No lowering of
  the front end's can help (a Java method's locals are what they are); the backend's way, such a
  local living in the frame, is `.todo/k19`.
- **Loops that keep their turn: decided, kept as stated in `deviations.md`.** Widening the rule
  ("A loop's turn") is paid in functions lowered twice (a turn in every enhanced `for`:
  `Handing` 153 resumable against 113), not in the loop (2 us in 1,024 rounds), and no rule
  found tells a loop reading a plain field another thread writes from one that does not. A
  daemon turning for ever keeps no component from ending or serving (below).
- **What the analysis reads coarsely, and what is refused: decided, kept.** A lock known by its
  static type and its `final` field, anything the flow does not follow: the corpus holds no
  region and no entry that may block but in the program sleeping in a monitor
  (`#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`), so precision buys nothing
  measured. Threads that all wait end the program (a JDK waits for ever: no test can hold on
  that, and the program is told why); `%java-now`'s refusal needs a wait in the run time's own
  printer; a Java file with no `main` that a Common Lisp program loads is refused on wasm where
  it starts threads: nothing would give the turns.

### What made functions resumable that cannot suspend (2026-11-22, l34)

**Invariant: what is lowered twice is what a thread can be suspended in, and the analysis,
not a library rewritten around it, keeps out what cannot be.** The shiori had 1,706 of 6,049
functions and lambdas resumable from five roots that feed one another (each alone under 1%,
all five 1,706 -> 234); a later shiori showed a sixth:

- **`Properties.load(Reader)` holds the table's monitor across `Reader.read%v`**, so through a
  socket reader every entry of a `Properties` could block, and with it `%string` / `%equals` /
  `%hash` and every caller of a map. A JDK 25 holds it too (measured: another thread's `put`
  waits out a `load` whose reader sleeps, as it waits out `forEach`, `compute*`, `merge`,
  `replaceAll` calling out; `store` and `list` call out without it), so `load` keeps it and
  reads only what its reader may be; `PropertyResourceBundle` reads its text first.
- **A hand-written `while (it.hasNext())`** turned through the dispatcher wherever an iterator
  synchronizes: it is a walk ("A loop's turn").
- **A `funcall` the lowering writes for the run time** (`Class.isInstance`, `Method.invoke`;
  `JavaEmitter.Context.madeCall`) reaches no lambda of the program.
- **A lazy class's initialization synchronized "by name"** (`Thread.currentName()` for its error
  writes a `volatile`): it runs once, so it gives no turn (`JavaResumable.initializes`).
- **A body synchronizing by name turned any loop** (`shop` 32 of its 37): bounded and decided
  loops take none.
- **The library's own loops turned on its own state** (`ZoneChanges.placed` reading a
  `volatile` cache, through YAVI's conversions 1,537 functions): a library loop turns only
  through what the program wrote.

Where no class under a value's class type declares `equals` / `hashCode` / `toString`,
`objectMethod` has the run time answer it (`runTimeAnswers`, on every backend):
`clazz.equals(HttpServlet.class)` reached `Properties.equals` (15 resumable functions of the
guestbook).

**Measured** (2026-11-22, wasmtime 51.0.0; component): the shiori 5,398,215 -> 3,719,642 B,
1,706 -> 234 resumable (resume bodies 2,171,995 -> 378,944 B), its `--native` executable
93,238,896 -> 38,796,784 B built in 332 -> 75 s, `wasmtime serve` compiling it in 193-198 ->
1.8-1.9 s, a request's time within a round's spread; `examples/java/mvcfn` 65 -> 16 of 1,787,
`examples/java/guestbook` 98 -> 17, `examples/java/shop` 37 -> 5. Pinned by
`JavaLoweringTest#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended` (a program of each
rule) and the corpus's `Turns`, whose daemon spinning on an atomic is why a decided loop must
move (else it gets no turn and the program never ends on either wasm target).

### What a receiver may be (2026-11-23, l47)

**Invariant: a dispatcher called on a receiver reads only the arms of what the receiver may
be -- the classes `new` made of it and the lambdas written --, and what a value may be is
found before what may suspend; anything the analysis does not follow may be anything, and a
call on it reads every arm.** Else a reader or a stream read through its abstract class
reaches every socket of the program: in a JDBC program any read of any stream suspends.

- **What a value may be** (`JavaResumable.Flow`, a fixed point of its own): the lambdas written
  and the structures a constructor makes, through calls' arguments, locals, slots, static fields
  and answers (a parameter answered as handed, as by `Objects.requireNonNull`, is each call's
  argument). Anything else may be anything: a literal, an array's element, a call of the run
  time, the parameters of a lambda the run time calls or of a function named as a value, a slot
  named quoted (a field updater).
- **One slot for every instance, not one per place an instance is made**: a function is lowered
  once, awaited or not, so one `InputStreamReader` of a socket makes `InputStreamReader.fill()`
  resumable for every reader; slots per place would buy nothing until a function is lowered once
  per what its receiver may be.
- **Flows first, then suspensions**: in one fixed point an arm read while a receiver was still
  nothing (so every arm) stayed read -- the shiori 213 resumable against 168.
- **The analysis runs once**, on the pass whose classes settled (`JavaLowering.resumed`): after
  every pass it cost the shiori's compile some 18 s, once 2.4 s.

**Measured** (2026-11-23, wasmtime 51.0.0; component): the shiori 4,155,515 -> 4,071,814 B, 251
-> 168 resumable, resume bodies 367,779 -> 267,156 B; with one
`settings.load(new StringReader(..))` in `main` 6,095,503 -> 4,078,053 B, 2,033 -> 168; its
compile 79-86 -> 65-68 s; the other examples byte-identical. What stays resumable in the shiori
waits: the driver's socket reads and connects, a loop polling a concurrent object in its test
(`Database.take()`'s `pollFirst()`), the pool's and the server's waits. Pinned by
`JavaLoweringTest#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended` (`Properties.load` of
a `StringReader` and of a `BufferedInputStream` over a stream a function answered, beside a
socket read through an `InputStreamReader`: none resumable, no region held).

### A lock no suspended thread can hold (2026-11-23, l48)

**Invariant: a `ReentrantLock` taken by `x.lock(); try { .. } finally { x.unlock(); }` is a
region as a `synchronized` block is: its `lock()` waits only where a region whose lock may be
the same object is held across a suspension, or such a lock is taken where no `try` gives it
back; otherwise it takes the lock at once, and the run time checks that it did.** On one stack
a `lock()` waits only for a holder suspended while it holds the lock.

- **A region** (`lockRegion`): a `lock()` statement followed by a `try` whose `finally` is that
  lock's `unlock()` alone, the lock read the same way at both ends (a local the `try` does not
  assign, a `final` field) and a `ReentrantLock`'s own (by name, or on what the flow finds a
  `ReentrantLock` and nothing else). Any
  other `lock()`, every `tryLock()` and `lockInterruptibly()`, is taken where no `try` gives it
  back. A region is held when its `try` reaches a suspension that keeps the thread's monitors, a
  wait on a monitor (which gives no `ReentrantLock` up), or a loop's turn (a thread holding a
  `ReentrantLock` still turns). A `Condition`'s `await` takes the lock again by a `lock()` of no
  region.
- **A `lock()` that cannot wait** reaches what the library's `lock()` reaches but its own wait
  (`Node.waitsOwn`), so its function is plain; `%java-locked` takes the settled future it
  answers, else refuses the program by name, as `%java-unforeseen` does a monitor.
- **Two fields made by their initializers are two objects** (`Lock.field`, for monitors too): a
  program may hold one lock across a socket read and take another at once.

**Measured** (2026-11-23, wasmtime 51.0.0; component): the shiori 4,071,814 -> 4,045,429 B, 168
-> 141 resumable (resume bodies 267,156 -> 236,740 B): out went jmustache's execution, whose
partials' lock is taken around a read that cannot suspend, nothing came in
(`ReentrantLock.lock()` stays resumable: lowered once, it is awaited where a `lock()` may
wait); the rest byte-identical but `Turns` (+55 B); the analysis 2.3 -> 2.5-2.9 s (18 -> 21
walks, the same fixed point). Pinned by
`JavaLoweringTest#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended` (its `Census`: a
region on one field beside a socket read takes its lock at once, one holding a socket read
waits, as does one whose lock `main` takes with no `try`; `Turns`'s sleeping holder's region
held, its two `lock()`s waiting) and `Turns` in `java-spec.yaml` on five legs.

### The host's events give the turns under a component (2026-11-19, k07)

**Invariant: a component never waits in the host while a thread of the program could go
on, and never loops over its threads to pass the time: a thread waiting for a time is
woken by a timer of the host's, one waiting for a reply by the reply, a served request's
by the request, and each gives the threads their turns where the host delivers it
(`%java-thread-pump`). Under `wasmtime serve` the task of a request that waits is
SUSPENDED, so the host enters the instance for other requests meanwhile.** Preview 1 and
`--native` keep the loop of "Resumable bodies", passing the time by `sleep`: Preview 1 has no
host future, and the runner's are not askable (`.todo/k18`).

- **Why** (wasmtime 49.0.0): a scheduler blocking in a host call held its instance -- a second
  request waited out a handler's sleep, a handler waiting for what another request brings was
  `%java-stuck`, a thread waiting for a reply kept a sleeping one from waking (the `waiting`
  program: 8.3 s and `never released`; pumped, 0.3 s and `released`) -- while the callback
  driver suspends a task whose root future is pending.
- **A HOLDER** (the start or a request's task) is who the host delivers to; whoever it woke
  pumps with no current task (`%task-swap nil`), so a thread's frame is resumed wherever its
  future is settled. A waitable joins the set of the task current when it is made, and a task
  that EXITS loses what its set holds (measured: an unawaited `(wait-for 1500)` under a handler
  that had answered never ran), so what a thread asks of the host is counted on its holder, and
  a request's task stays in hand until its thread ended AND it holds no reply in flight and no
  armed timer (`%java-carrying`). Rejected: the same in the runtime (`_task_finish` staying WAIT
  while a waitable of its set lives): it would hold every served Lisp program's task for each
  losing timer of a read deadline (`%future-race`), a blast radius no measurement asked for.
- **Timers**: one armed, for the soonest deadline; a released `await(1, HOURS)` holds no task.
  Under `serve` the start's holder is `gone` once no thread can go on, and the first request
  arms again what it armed.
- **Replies** are read from the send, not the wait (`%java-http-launched`, renamed from
  `%java-http-exchange` by `JavaResumable.rewrite`): `sendAsync` sends on one thread and waits
  on another, and the send's subtask is in the set of the task current THEN.
- **The end** (`wasmtime run`): a pump on the start's holder ends when only daemons are left, or
  the alarm that woke `main` resumes a daemon turning for ever, for ever (the corpus's `Turns`);
  with nothing held while a thread that is no daemon lives, `%java-stuck`.
- **Threads that go on turning** (a daemon spinning, `while (!flag) Thread.yield()`) count the
  host as another thread while a server runs or a reply is in flight, so they join the line; a
  pump that has run 64 turns and 10 ms stops with one 1 ms timer on its holder
  (`%java-thread-nap`; one per request leaked a task each, +50 ms a request). A served program
  with a spinning daemon answers in 40 to 120 ms.
- **A request under `serve`** (`%java-http-request`) answers the future of `%java-http-held`, a
  frame of the request's OWN task that pumps and awaits its bell until the thread ended, the
  response ended (an exchange outlives its handler) and nothing is held. The transport awaits it
  (`.kb/http-server.md`), and `JavaResumable` marks the function holding `%java-http-request`
  awaited, or `%java-now` would refuse the pending future.
- **A response leaves through its own task**: `task.return` is the host's notion of the current
  task, and a thread takes its turns on whichever task the host woke, so a response queues in
  its holder and is sent where that task runs or once the head is out (a chunk and the end are
  the stream's, any task's); else the holder's bell rings.
- **Measured** (2026-11-19, wasmtime 49.0.0): a request
  0.4 s after one to a handler sleeping 1.5 s, or asking a 1.5 s origin through
  `HttpClient.send`, is answered at once by the SAME instance; a task scheduled 700 ms past its
  handler's response ran. Which instance a request reaches is the host's: of 4 at once to a warm
  instance 9 of 12 shared it; after the idle second 4 at once started 4.
- **Sizes** (component B): a server of one context 435,274 -> 454,127 (+4.3%: holders, pump and
  timers 5 KB, three more resume bodies 4 KB, the delayed response's two steps 3 KB,
  `%java-http-send` 2 KB). **Trap**: a `lambda` written in the value of a `(setf (aref ..) ..)`
  was compiled four times (2,178 B each, measured); `%http-delayed-begin` binds what it stores
  first.
- **Pinned by** `java-http-server-spec.yaml` `a-request-that-waits-is-ended-by-another` (`/held`
  writes a chunk and awaits a latch; `/release` is asked once the chunk is read where it is sent
  as written, a JDK and the component, beside it elsewhere: `meanwhile:`),
  `java-http-spec.yaml`'s `waiting` program (no `--native` leg: `.todo/k18`), `JavaLoweringTest`
  (the three variants, the loop, the exchange's two names, the responder handed over), the
  corpus's thread cases on the component leg.

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
| `new int[n]`, `{1, -2}`, `xs[i]` | `(make-array n :element-type '(unsigned-byte 32) :initial-element 0)` (each primitive its own, "Numbers"); `(vector ..)` / `:initial-contents (list ..)`, of integral constants `'(1 4294967294)` (what the packed array stores, folded to a packed literal: "java.security and javax.crypto", "Sizes"); `(%mask-signed-field 32 (aref xs i))` |
| `while`, `for`, `do` | `(while test body.. update..)`; `(block %break ..)` around it and `(block %continue ..)` around the body only when the body has that exit; `do` tests a flag set after the body |
| `for (T x : xs)` | an array: a counter, the length taken once; an `Iterable`: `(let* ((it (iterator xs))) (while (hasNext it) (let ((x (next it))) ..)))`, each call direct or dispatched as any call |
| `return v` | the value in tail position, else `(return-from %fn v)` and a `(block %fn ..)` |
| `switch (n) { case 1 -> a; default -> b; }` | `(cond ((eql n 1) a) (t b))`; a statement's `break` is `(return-from %break nil)` |
| `yield v` | the value in tail position of its arm, else `(return-from %yield v)` |
| `o instanceof Circle c` | `(and (Circle? o) (progn (setq c o) t))`, `c` a `let` of the statement; `if (o instanceof Circle c) a` is `(if (Circle? o) (progn (setq c o) a))` |
| `new Inner()`, a local class | `(Outer.Inner.new outer args.. captured..)` |
| `new int[n][m]` | `(let ((a n) (b m)) (let ((n' (%java-array-length a)) ..) (declare (type (integer 0 2147483647) n' ..)) rows))`, each level but the last a `while` filling a vector of `nil` |
| `f(1, 2)` to `f(int...)` | `(f (make-array 2 .. :initial-contents '(1 2)))`; `(list a b)` where an element is no constant |
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

javac's class, the spike's lowering and this front end's; s, JVM / precompiled wasm; bytes,
`.class` / `.wasm`:

| program | javac | spike | front end | bytes, spike -> front end |
|---|---|---|---|---|
| `Fib` | 0.14 | 0.21 / 0.18 | 0.20 / 0.17 | 25,978 / 5,194 -> 20,746 / 2,728 |
| `WrapInt` | 0.18 | 0.23 / 0.11 | 0.20 / 0.11 | 28,119 / 5,017 -> 21,995 / 2,508 |
| `Sieve` | 0.21 | 1.20 / 0.57 | 0.59 / 0.53 | 41,446 / 11,136 -> 37,366 / 10,819 |
| `WrapLong` | 0.12 | 3.57 / 7.74 | 0.14 / 0.04 | 27,604 / 7,049 -> 21,534 / 2,539 |

A step, the start
subtracted: `WrapLong` 1.3 / 1.2 ns, `WrapInt` 2.8 / 2.4 ns.

### j77 (2026-11-17)

The variant is the program's IR (`--dump-ir`) edited by hand; JVM / precompiled wasm /
precompiled component, s:

| question | as lowered | variant | verdict |
|---|---|---|---|
| a pattern's binding, 10^8 `if (s instanceof Circle c) .. else if (s instanceof Square q) ..` | 1.08 / 3.57 / 3.62 | `let` in the taken branch 1.09 / 3.25 / 3.37; the variable's stores at the head of the branch 1.05 / 3.24 / 3.38 | the cost is the `and`'s truth value, not the variable: `branch` |
| a dense `int` switch, 10^8 `step(i % 12, acc)` | 2.30 / 1.02 / 1.02 | `case`: the same `eql` chain | nothing to choose |
| a lazy class's test, 3*10^8 static calls | 0.96 / 5.31 / 5.31 | the class eager 0.99 / 4.93 / 4.93 | 1.3 ns a call on wasm, none on the JVM |

- The switch: a comparison costs 0.56 ns on wasm; javac's class takes 0.28-0.36 s, if-chain or
  `tableswitch`, and the JVM's 2.30 s is the method's once every arm runs, not the chain's
  (0.36 s with 8 cases taken, 1.95 s with 16; [hot-path-method-size.md](hot-path-method-size.md)).
- `examples/java/shop`'s `Discounts` stays lazy: its initializer makes a method reference, and a
  lambda body's effects count as the maker's. Not changed: leaving them out needs every call
  through a functional interface, an intrinsic's `funcall` included, to count its lambdas'
  bodies, to save 1.3 ns on wasm only.
- An anonymous class inside a lambda: a JDK 25 numbers it in the order written, the call around
  it resolved among overloads, and so does the front end (pinned:
  `a-local-and-an-anonymous-class-keep-the-locals-they-read`).

### j78 (2026-11-17)

`--dump-ir` edited for the variant; JVM / precompiled wasm / precompiled component, s; the
interpreter's at a fiftieth to a tenth of the rounds:

| question | as lowered | variant | verdict |
|---|---|---|---|
| the cast a compiler puts where a value is read through a type argument (`xs.get(i).length()`, `ns.get(i)`) | 1.20 / 2.69 / 2.76; 4.15 | `(or (null v) (simple-string-p v))` / `integerp` before each use: 1.07 / 3.79 / 3.66; 5.08 | trusted: the JVM pays nothing, wasm 6 ns an `integerp` and 18 ns a `simple-string-p` (+40% on this loop) for a heap only a raw type or an unchecked cast pollutes |
| `(or box (%java-null-unbox))` where the box was just made | 0.39 / 1.93 / 1.90; 8.65 | the test dropped: 0.35 / 1.82 / 1.83; 7.15 | kept: 0.17 ns a test on wasm, none on the JVM; only the interpreter would gain, for a flow analysis of where a box was made |
| the `Long` cons now that `long` arithmetic is one machine operation (boxing loops) | 1.56-1.69 / 1.78-1.82 / 1.83; 11.33 | a `Long` the bare integer: 1.44-1.46 / 1.46-1.61 / 1.49-1.51; 10.73 | kept: 10-20% of a loop that does nothing but box, and the cons is what tells a `Long` from an `Integer` (`equals`, `getClass`, a pattern, a `HashMap` key) |

## What a compiler reports and this does not, stated in `doc/*/java/deviations.md`

Checked: `private` (the unit is the top-level class), the access of a class or member from
another package ("A program of several files"), assignment to a `final` field or local, what
the flow decides ("The flow": unreachable statements, a missing `return`, definite assignment
and unassignment, effectively final captures), a written type argument outside its bound, a
field written through `? extends`, checked exceptions ("Exceptions"). Warned: the unchecked
warnings ("Warnings"), a catch clause nothing reaches. A value read through a type argument is
trusted ("j78"), a store into an array of references unchecked (measured: "The class of an
array"). The run time's exceptions are the language's, but a `NullPointerException` has no
message, a `ClassCastException` says nothing of modules and loaders, and one out of a static
initialization is not wrapped ("Exceptions").

## Tests

- **`JavaSpecE2eTest` over `java-spec.yaml`** (in `./mvnw test`): the cases in one file behind a
  class calling each `main`, run under the JDK running the test (the oracle leg: an expectation
  that is not the JDK's fails there), the interpreter, the JVM, wasm and the component; on the
  JVM the corpus, past one class's constant pool, loads `$PartN` classes
  (`JavaSpecE2eTest.runOnJvm`). A `standalone:` case is a file of its own (`at:`, `stderr:`,
  `files:`, `file:` as in `a-program-of-several-files-finds-its-classes-by-package`,
  `directory: true`, `modes:`: "Files"); a `refused:` case is refused by the JDK with `error` at
  that line and by the front end at the same line with a message starting the same. The
  interpreter's `standalone:` leg has the other legs' 300 s (`CliStack.callWithin`), fails by
  name where threads wait for ever and ends the threads left ([threads.md](threads.md), "A
  program's end ends its threads").
- **No thread case orders two threads by a sleep or a delay** (k14): with the legs beside each
  other under load a sleep orders nothing; a latch or a wait for the state the line prints
  (`while (..) Thread.sleep(1)`) does. A sleep stays where it IS the subject; a limit whose
  expiry would change a line is 60 s; `invokeAll`'s in `Handing`, which HAS to pass, is asked of
  a pool of one thread started beforehand. `Turns`' loops say their first turn through a
  `Runnable` (over a plain field a loop keeps its turn on wasm). A JDK's `ThreadPoolExecutor`
  text may say `pool size = 1` after `awaitTermination` answered true: the case waits for it.
- **`JavaLoweringTest`**: the table as emitted forms, names, constants, the refusals and compile
  errors with positions; `#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the structures'
  names, a field through a qualified `super` refused, and what a program reaches and no more:
  `Hooked`, `Utf16`, a class object, the stream classes, the fetch form's timeouts);
  `#aProgramOfSeveralFilesIsReadFromItsSourceTree` (an in-memory `JavaFiles`);
  `#aLibraryOfTheSourcePathIsLoweredAsFarAsTheProgramReaches` (a reached body's refusal with its
  `reached through` lines, a class nobody has: `java.util.Currency`; the access, annotations);
  `#anErrorTheLoweringDependsOnIsReportedAtItsPosition` (an array cast no value passes, which
  flow error first, the columns);
  `#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition` (`Class.getInterfaces()`);
  `#theLibrarysSourceIsJavaAJdkCompiles` (the library's clauses against the JDK's);
  `#aMonitorIsEnteredAndLeftAndAThreadIsTheRunTimes` (a failing initialization's two forms,
  `%uncaught`); `#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended` (above).
- **Tools.** `a-program-uses-a-library-read-as-source-from-its-source-path` (`standalone:`,
  `library:` / `provided:`);
  `JavaClassPathCliTest#aJavaProgramCompilesItsLibrariesFromTheSourcePath` (the two options, the
  coordinates); `SourceLanguageTest#javaClaimsItsExtension` (`isComplete`);
  `RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` (a session on a pipe;
  `test` and `format` refusing);
  `PlaygroundReplTest#theJavaPickKeepsItsDeclarationsAcrossBuffersAndRunsAProgram`;
  `SchemeLibraryTest#aCommonLispProgramReadsNoFrontEndsLibrary` (the gate); `DocExamplesTest` (a
  ` ```java ` fence is a whole program, its output the plain block after it; `deviations.md`'s
  pins the class of an array no record names); `ExamplesE2eTest` (`examples/java/shop/Main.java`).
- **The language.** Refused: `a-member-of-an-inner-class-typed-through-the-arguments-around-it`,
  `a-type-argument-outside-its-parameter-s-bound`, `a-field-written-through-an-extends-wildcard`,
  `a-field-through-super-in-a-static-method`,
  `a-private-field-of-a-superclass-is-not-read-by-its-simple-name`,
  `a-private-method-of-a-superclass-is-not-called-by-its-simple-name`, and cases of the
  annotations and of each definite-assignment and reachability error. The corpus cases
  `a-loop-is-a-while-and-its-exits-are-blocks` (what the flow accepts),
  `a-nested-class-is-static-or-holds-the-instance-around-it` (`o.super(..)`, `o.new I() { }`,
  `Outer.super.m()`), `an-interface-has-defaults-static-and-private-methods-and-constants`
  (`I.super.m()`), `a-class-is-a-structure-and-a-constructor-a-function` (`ClassesHiding`),
  `a-local-and-an-anonymous-class-keep-the-locals-they-read` (`LocalBase`),
  `the-methods-of-object-on-a-class-that-declares-them-and-on-one-that-does-not` (`classes`),
  `a-type-argument-is-found-from-the-arguments-the-target-and-the-lambdas`,
  `an-array-is-a-vector-with-the-default-of-its-element-type`. `a-class-is-a-value` is
  `standalone:` so that the corpus program holds no class object; refused beside it:
  `a-class-held-compared-with-a-literal-of-another`,
  `a-class-literal-assigned-to-the-class-of-another`, `two-class-literals-of-two-classes`,
  `a-class-literal-of-no-class-under-the-object-s-type`.
- **Exceptions.** `a-static-initialization-that-throws-leaves-its-class-unusable`,
  `the-run-time-raises-the-language-s-exceptions` (`charAt`: each arm of `%string-code`;
  `compared`), ci-spec `string-code-reads-a-character-s-code-or-minus-one`, the `standalone:`
  `an-uncaught-exception-of-the-program-s-own-class` (`at:` the first line starting `  at `),
  `the-class-the-program-starts-at-fails-its-initialization`,
  `an-uncaught-exception-ends-its-thread-alone`. Checked: the 13 `refused:` cases from
  `a-checked-exception-thrown-and-neither-caught-nor-declared` to
  `a-throws-clause-of-no-throwable`; `checked` and `named` in
  `try-catch-finally-over-one-condition-holding-the-thrown-object` (what must be ACCEPTED: the
  rethrow, the inferred clause, `close()`, a `finally` that drops); the last warning of the
  `standalone:` `the-unchecked-warnings-are-printed-before-the-program-runs` (`warnings:` the
  JDK's and the interpreter's standard error, the compile's for the other legs); ci-spec
  `a-cleanup-a-return-from-runs-is-outside-every-handler-it-left`.
- **Library classes.** `a-byte-stream-reads-and-writes-bytes-and-a-string-is-its-bytes` (`wide`: the
  UTF-16 charsets), `a-print-stream-a-reader-and-a-writer-over-streams-and-system-out-as-a-value`,
  `the-standard-input-is-read-as-bytes-and-through-a-reader` (`standalone:`; `stdin:` is what
  every leg reads), `the-streams-that-hold-nothing-take-back-follow-one-another-and-count-lines`,
  `a-matcher-keeps-a-region-tells-what-it-read-and-appends-its-replacements`,
  `format-renders-the-conversions-programs-write` (the `Locale` forms; `hashes`),
  `a-string-has-its-everyday-surface-and-two-constants-of-a-text-are-one` (`rest`),
  `the-boxes-parse-print-and-compare-and-math-is-strict` (`ulp` .. `scalb`; `bits`),
  `a-deque-an-optional-the-utilities-and-the-older-classes` (`later`),
  `the-functional-interfaces-of-the-library-are-source-in-the-subset` (`conversions`),
  `a-duration-counts-seconds-and-nanoseconds`, `threads-wait-are-notified-and-interrupted`
  (`Thread.sleep` of a `Duration`),
  `dates-times-and-instants-are-read-from-their-texts-and-computed-with` (where a JDK lists fields
  in an order of its run, the words before them); java-http-spec
  `a-request-and-a-connection-have-time-limits`, `fields-both-ways` (`firstValueAsLong`),
  `subset` (a request class's timeout). A corpus program holding a lone surrogate makes it with
  `new String(char[])`, never as a constant (wasm reads a constant's as `?`).
- **Streams.** `a-stream-hands-its-elements-on-one-at-a-time` (`closing`, `several`),
  `the-streams-a-program-writes-over-collections-arrays-texts-and-numbers` (the compensated sum
  of 200 seeded doubles), `a-collector-gathers-a-stream-and-the-type-arguments-are-found`
  (`Gathering`: the inference over the program's own classes),
  `a-gatherer-is-a-stage-holding-what-it-pushes-until-it-is-asked`; `numbers` in the
  spliterators' case, `drawn` in the streams' case, `concurrent` in the collectors' case. A line
  where this differs from a JDK by decision (a `push` in the middle of an element, a `peek` after
  `mapMulti` under a `limit`, a stream's spliterator's marks) is in no case, the JDK leg would
  refuse it; the probes of the k26 artefacts hold them.
- **Thread cases.** The `standalone:` cases `threads-are-started-joined-and-hold-monitors` (the
  classes a JDK synchronizes), `locks-latches-queues-and-executors-hand-work-between-threads`
  (`stamps`, `phases`, `transfers`, `hooksAndSizes`) and
  `threads-share-concurrent-collections-and-completable-futures` (`accumulated`, `stagesLeft`,
  `completers`; `mapped`: `mapConcurrent`), both with static methods called from the end of
  `main` (`shown` drops the object's number);
  `a-scheduled-pool-and-a-timer-run-tasks-at-their-times` (`asked`; where an order by time is
  asked, delays of 600 against 20 ms, and what two threads ran sorted before it is printed);
  `threads-wait-for-one-another-again-and-again` (threads
  suspended and resumed: a buffer of two, a held monitor's entry, cleanups and catches that
  wait, `invokeAny`, a barrier's second round);
  `JavaSpecE2eTest#aProgramWhoseThreadsAllWaitIsRefusedWhereThreadsTakeTurns`.
