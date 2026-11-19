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
`%http-serve-begin`, a wasi:http component under `--component`): k04 (2026-11-19); what
comes after is `.todo/k05`, `k06`, `k07`, `k08` and `k11` (what j98, j99, k00, k01, k03 and
k04 left).

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
- **`int` is an integer kept in range** by the unconditional mask
  `(- (logand (+ x 2^31) (2^32 - 1)) 2^31)`, `short` and `byte` the same at 16 and 8 bits;
  `long` is `(%mask-signed-field 64 x)`; `char` an integer whose static type prints it, a
  `float` the double of its value. No declaration is emitted ("Numbers" below).
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
- **The program runs inside `(%reporting-uncaught #'%java-uncaught-line (%host-exit 1) init.. (main))`**:
  what nothing caught is a JDK's line, the pipeline's location lines under it and the status 1
  on four backends, with no handler holding the condition ("Exceptions" below).
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
  ask). **No object stands for a class** (j98): `getClass()` and a class literal
  (`Name.class`, `JavaSyntax.ClassLiteral`) are read where one is compared with the other
  (`==`, `!=`, `.equals`: `equal` of the two binary names, `JavaLowering.sameClass`) and
  before `.getName()` / `.getSimpleName()`; anywhere else each is refused by name. That is
  the `equals` an IDE writes (`getClass() != o.getClass()`), the one use a probe of
  everyday code met. With `==` the compiler's `incomparable types: Class<..> and
  Class<..>` is kept as javac gave it over 16 shapes (two literals of two classes, a
  literal of no class under the object's type, two objects of unrelated classes), but
  one: an interface beside a `final` class that does not implement it is compared. A
  `Class` as a value (a field, an argument, `isInstance`) is `.todo/k05`. `super.toString()`,
  `super.equals(o)` and `super.hashCode()` with no class above declaring the method are
  those three run-time functions on `this` (j89; refused by name until then, and any other
  member of `Object` through `super` still is). The identity text names a structure by its
  own name, which is the class's binary name since k01 (until then the Lisp name:
  `Outer.Inner@1f`, `ThreadLocal@1f`). Cost: the longer names of the made classes of
  `java.lang` (a counter under four threads 109,069 -> 109,253 B of wasm), nothing to a
  program that makes none.
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
- **No other file**: a session has no source tree, as `-e` (the REPL's working directory is no
  package root, and a snippet shell reads none either).

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

Parsed and dropped (`JavaParser.annotations`), but a name that is no annotation type of the
library -- `java.lang`'s five, simple or qualified -- is `cannot find symbol: class X` (a
`Known` error, which a probe looking ahead reports): the subset has no annotation type of a
program, so such a name is javac's error too, and it is the kind a processor or a framework
gives a meaning whose absence would change the program. None of the five changes a program
that compiles. `@Override` and `@FunctionalInterface` ride in the modifiers and are checked
(`JavaLowering.overrides`: a supertype's or `Object`'s instance method of the signature seen
from the class, or a record's accessor; `checkFunctional`). `@SafeVarargs` rides there too and
`@SuppressWarnings("unchecked")` is a span of the file (j86, "Warnings" below).

## The tools (2026-11-17, j74)

`SourceLanguage.isSourceFile`'s readers: `rontolisp build` names no system (as before);
`rontolisp test` refuses a `.java` file (no test framework; a program's status is its verdict:
it used to read the file as Lisp, run it and report no tests); `rontolisp format` refuses a
`.java` and a `.star` named explicitly (re-indenting is no whitespace-only change there; a
directory walk never took them). The Maven plugin compiles `.lisp` under `src/main/lisp` only
(`LispSourceSet.EXTENSION`): a `.java` file of a Maven project is javac's, and a subset
program compiled to a class of the same name would collide with it.

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
  `Outer.this` reads it there (`enclosing`).
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
below). `lowerClass` runs `JavaFlow.check` once the class's members and static
initialization are lowered; a library class is not checked. Reported as the compiler reports,
the first problem of the kind it reports first: reachability, then assignment, then
exceptions, then captures; of one kind the first in the ORDER OF THE CLASS'S MEMBERS, the
static initialization and a default constructor ahead of them (j99, asked of javac 25: a
method written ahead of a constructor is reported first; until then every constructor went
ahead of every method). A class with an error of typing gets no flow at all, and a class
after one with a flow error is typed but not flowed, which is why the first error is all
that is reported.

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
  conditional of them (`needsTarget`, `accepts`).
- **A lambda's shape picks among overloads**: its parameter count, and whether its body can
  give a value or none. Only where several candidates remain is a body that is a call asked
  whether it gives nothing (`givesNothing`: lowered once and dropped, state restored), and only
  for a lambda that writes its parameter types or has none -- as the language does. A
  functional interface giving a value is more specific than one giving none.
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
  (`ArrayList.stream()`), never `cannot find symbol`.
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
- **`==` of two strings is `eq`**, and the constants of a program holding one are globals set
  through `%java-intern` (`JavaClasses.identicalStrings`; the program is lowered again), so two
  constants of a text are one object and `intern()` answers it. A program with no such
  comparison is unchanged. `new Object()` is `(make-array 0 :fill-pointer 0)`: no other value
  of a program is a vector with a fill pointer that is no string.
- **`String.format`** is `%java-format` over the argument array and the program's `%string`;
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
  printed 43,929 / 56,779; a `HashMap` put and read 37,802 / 57,847. `.todo/k05` holds what is
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
  `IndexOutOfBoundsException` of its range. Not there: `results`, `splitAsStream`
  (streams), `usePattern`, `namedGroups`.
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
  `getNumericValue`, which still ask the host: a digit outside ASCII is read on the
  interpreter and the JVM and not on wasm, `Integer.parseInt` with it (`deviations.md`,
  `.todo/k05`: a table there would be in every program that parses a number).
- **`%h` hands the format the program's `hashCode()` only where the control may ask**
  (`JavaIntrinsics.formatted`): `%java-format` takes the function as its fourth argument,
  `nil` when the control is a constant with no `%h`. The arm itself is 401 B in every
  program that formats (154,299 -> 154,700 B).
- **A field of an intrinsic class naming a source class** joins the class as its members
  do (`named` / `meets`; `select` brings it): `String.CASE_INSENSITIVE_ORDER`, read
  through `rontolisp.internal.CaseInsensitive.order()`.
- **`System.getProperty` and `Runtime` answer what every target can**: the separators
  and the encodings, `null` for any other key; one processor; `gc` is nothing.
  `totalMemory` and its kind stay refused.

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
  on wasm no `equals` of the character they spell (`.todo/k05`).
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
  `started()` became resumable in a program with a `BodyPublisher` and a `sendAsync`
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
- **The wait for a reply is a suspension that gives one turn**
  (`JavaResumable`: `%java-http-reply` -> `%java-suspending-http-reply`, "general" as a
  sleep is): the threads that can go on take a turn, then the thread waits for the host
  and no other thread runs -- a thread asleep included. A wait the scheduler shares with
  its timed threads needs a host future that can be asked without waiting, which neither
  wasm target has: a `--native` fetch's future is deferred (it settles in the await),
  the component settles a host future only while something blocks on its scheduler
  (`.kb/async-await.md`, "`%future-settled-p`"), and `%future-race` is `sockets.lisp`'s.
  Yielding while others can go on was rejected: a thread spinning on a flag the reply's
  dependent sets never lets the reply in. `.todo/k07`.
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
- **Not made** (`.todo/k05`): timeouts (`java.time.Duration`, and fetch has none:
  `.todo/148`), bodies as they arrive (`ofInputStream` reads a body already in,
  `ofLines`, a program's own `BodySubscriber`), the `--host-fetch` reactor as a leg.

## com.sun.net.httpserver (2026-11-19, k04)

**Invariant: a server is the lowered program's own `rontolisp::%http-serve-begin` call, so
every transport a served application has carries it and none is new; the API is the JDK's;
an exchange answers through Clack's delayed response, whole or through its streaming
writer, which the transport that can sends chunk by chunk.** No class of a package of our
own was needed (`deviations.md`, "The HTTP server").

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
  `(handler-case (%http-serve-begin (lambda (env) (lambda (responder) (%java-http-request serial (lambda () (HttpServing.answer server env responder))))) port host) (error (c) (princ-to-string c)))`**
  where it is called, so the forms the seam hands on NAME the transport:
  `HttpServeLibrary` (first pass of `expand`) splices the target's leg, and under
  `--component` the directive inside it makes the output a `wasi:http/handler` component
  (`.kb/clack.md`, "Transport selection"). A program reaching no member of the server
  holds none of it. A plain Preview 1 module gets the directive's call-time error, which
  `create` throws as an `IOException` (`HTTP-HANDLER requires --component ..`).
- **The two-step serve (`http-serve.lisp`).** `%http-serve` either blocks or cannot be
  joined later, and a Java program goes on after `create` and ends when its server
  stopped. So: `%http-serve-begin app port address` binds and answers the server at once
  on every leg (the socket seam's handle; on `--native` `#(listener app stopped)` after
  the runner's `serve`, which accepts and queues from then on; `t` where the host owns
  the port), `%http-serve-run server` answers once it stopped (the join on the socket
  legs; **the serving loop itself on `--native`**, until `%http-serve-stop` set the flag;
  at once under a host), `%http-serve-port` the bound port or nil. `%http-serve` is
  unchanged: Ring's `:join? false` on `--native` still never returns.
- **When `main` runs, and the program's end.** `bind` (so `create`) binds; `start` opens
  a gate; **the lowering appends `HttpServing.serve()` after `main`** in a program
  reaching the server (`JavaLowering.serves`): `run0` is
  `(progn (%java-http-wait) (%http-serve-run handle))`. Interpreter / JVM: the thread the
  program started on parks (a ticket on its carrier, taken by the last `stop`; a park so
  that the interpreter's `System.exit` on a request thread wakes it, "Threads"), then
  joins the transport. `--native`: the wait is nothing and the run is the loop, so
  requests are answered once `main` returned. Component: both nothing; the host
  instantiates the program, which runs `main` to its end, and calls `handle`. A `main`
  that waits for ever after `start()` is therefore "every thread waits" on both wasm
  targets (measured; `.todo/k11`).
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
- **A handler runs on the transport's thread** (`%java-http-request`). The run time
  never started that thread, so `%java-carrier` is BOUND to nil around the request and
  made at first ask: without it every request thread found the global carrier of the
  thread the program started on and WAS that thread -- `Thread.currentThread()`, a
  `wait`, an interrupt, the monitor entered last (the corpus's `/thread`; a
  `synchronized` still excluded, by the host mutex under the monitor: 16 requests at
  once, 2,000 increments each, counted 32,000 with and without the binding). `bind` also sets
  `%java-shared` / `%java-main` as the first `Thread.start()` does, and reaching the
  server turns `JavaClasses.monitors` on where the target has threads
  (`!suspended && isServer(owner)`): a served program's `synchronized` is real with no
  `Thread` member in it. No executor: one host mutex around each handler (a JDK's
  default runs them one at a time). An executor: the transport's own
  thread-per-request, and the executor is handed nothing -- calling `execute` would make
  the transport thread wait for a thread of the program's, a suspension on one stack.
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
- **Threads of the program on one stack**: a program that starts threads and serves
  works while its handlers do not wait; a handler that waits for a thread (or sends a
  request through the client there) ends the program with `a thread waiting inside a
  method the run time calls ..` (the transport calls the handler; `.todo/k11`).
- **Sizes** (2026-11-19, wasmtime 49.0.0; class / component bytes): a server of one
  context answering `hello` 300,455 / 379,750; `examples/java/http/Server.java` (four
  contexts, a query split, a chunked body) 480,883 as a component.
- **Pinned by** `FetchSpecE2eTest#javaHttpServer` (`java-http-server-spec.yaml`: the
  program started as a server under the JDK running the test and on the interpreter,
  the JVM, `--native` and `wasmtime serve`, 22 requests in turn, the log, the exit once
  stopped; `/stream` asks the test's origin whether its first chunk was read before it
  writes the second -- `seen` on the JDK and the component, `unseen` where the body goes
  whole), `HttpHandlerTest#stoppableSeamServesJoinsAndStops` (the stop by a handler),
  `#directiveSupportsTheDelayedResponse` (the writer on the interpreter's mirror),
  `JavaLoweringTest#theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches` (the begin
  form, the wait after `main`, a monitor held on the host and none on one stack),
  `#theLibrarysSourceIsJavaAJdkCompiles`, the `doc/*/java/index.md` example,
  `examples/java/http/` (`ExamplesE2eTest`).
- **Not made** (`.todo/k11`): a body sent as it is written on the interpreter, the JVM
  and `--native`; the values of a field sent twice; a handler on the executor's thread;
  a `main` or a handler that waits on one stack; `HttpsServer`, `Filter`,
  `Authenticator`, `HttpHandlers`; name lookup.

## Numbers (2026-11-17, j72)

- **`long` is `(%mask-signed-field 64 x)`** (`JavaForms.wrap`), the primitive the Clojure
  front end's unchecked verbs use (`.kb/clojure-frontend.md`): both compilers fuse it with the
  operation under it into one wrapping `ladd`/`lmul`, `i64.add`/`i64.mul`
  (`.kb/jvm-int-fusion.md`, `.kb/wasm-int-fusion.md`), where the mask of an `int` at 64 bits
  needs the bignum literal `2^63`, which declines fusion: every step built a bignum. `x >>> k`
  with a literal `k` is `(logand (ash x -k) (2^(w-k) - 1))`, no bignum either.
- **`int` keeps the unconditional mask.** `(%mask-signed-field 32 x)` measured the same time
  (`WrapInt` x10: 0.85 / 0.77 s against 0.91 / 0.74) and `Sieve.wasm` 10,380 -> 10,988 bytes
  (its fallback is `logxor`'s): not made. A mask in a `let` or behind a test loses 4x (the j68
  artefact README).
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
- Constant variables are set first; a field holds its type's default before its initializer
  only when something can read it there (no initializer, a lazy class, a read through a method
  the initialization calls or by a qualified name ahead of the field).
- The decision is made at the end of a pass and the next pass is lowered with it.
- **An initialization that throws an object ends as the language ends it** (j88): "A static
  initialization that throws" under "Exceptions".
- Not the language's: a run-time error inside an eager initialization surfaces before `main`
  (reported as the JDK reports an uncaught one), and an error the run time raises inside any
  initialization is neither wrapped in `ExceptionInInitializerError` nor remembered by the
  class (stated in `deviations.md`, `.todo/k06`).

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
  (`%java-array-length`, every length of `%java-new-array` first); `charAt` (the host's
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
  still not wrapped: `.todo/k06`.
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
  "A condition's `:report` is what PRINTS it"), `.todo/k06`'s lead.
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
  - **Not as javac**: a local class's flow is checked where it is lowered, ahead of an
    earlier method of the class around it (one error is reported, so it shows only in a
    program with two). `Object.clone()` and `finalize()` are no members here. A type
    variable thrown is named without the `where` lines javac adds.
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
  What is left of the list j73 made is `.todo/k06`'s: the host's failures in an
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
  members that are `java.lisp`'s "threads" section, which has TWO variants under the same
  names, `#-rontolisp-wasm` and `#+rontolisp-wasm`: `JavaLibrary.forms(oneStack)`,
  `CompileFrontend.expand` passes `wasm`, the interpreter reads the host one, the pruner
  takes the names of both (`everyVariantForms`). The wasm variant has, beside each function
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
  `ConcurrentHashMap`'s long chain; no `BLOCKED` state; the handlers not asked for `main`'s thread; an atomic is no
  `Number`; thread numbers are ours; a class literal only as a `synchronized` statement's
  lock (the quoted structure name, the object a `static synchronized` method holds).
- **Pinned by** `java-spec.yaml`'s `standalone:` thread cases (a `standalone:` case so
  that the corpus program starts no thread and keeps the thread-free lowering),
  `JavaSpecE2eTest#aProgramWhoseThreadsAllWaitIsRefusedWhereThreadsTakeTurns` (the two
  wasm legs), `JavaLoweringTest#aMonitorIsEnteredAndLeftAndAThreadIsTheRunTimes` (the
  host's forms, then the resumable ones) and
  `#theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended`.

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
  `defaultExecutor`, and every `..Async` member on `CompletionStage`. Still not there:
  `.todo/k08`.
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
  and this front end types every signature of a class it reads. `Class` is a type with no
  value: a class literal and a `getClass()` kept are refused as before.
- **A compact source file brings the three packages** (`LIBRARY_PACKAGES`): the atomics were
  not brought before.
- **Cost to a program that starts threads** (the members `Thread` gained; class / wasm
  bytes): a counter under four threads 172,141 / 99,263 -> 174,715 / 100,731;
  `examples/java/shop` 414,747 / 301,780 -> 418,004 / 303,382. `Fib` and `Sieve` are
  byte-identical.

### A thread suspended and resumed: the two leads measured (2026-11-17, j83)

The second is made (k00, 2026-11-18: "Resumable bodies" below); the first waits for
wasmtime (`.todo/k07`). j89 made the first step of the second lead: "The regions a
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
target and `Thread.start()` is reached); a thread that cannot go on awaits a future of its
own, and the thread the program started on settles the futures of the threads that can, the
one waiting longest first (`java.lisp`'s `#+rontolisp-wasm` "threads").** The interpreter
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
  drops its integer fusions (`.todo/k07`).
- **Not made**: a Java file a Common Lisp program loads that starts threads and has no
  `main` is refused on wasm (nothing would give the turns); `getState()` still never
  answers `BLOCKED`. `.todo/k07`.

## The lowering table

| Java | core forms |
|---|---|
| `a + b` (int) | `(- (logand (+ (+ a b) 2147483648) 4294967295) 2147483648)`; `/` is `truncate`, `%` `rem` |
| `a << n`, `a >>> n` | `(ash a n)` wrapped; `(ash (logand a mask) (- n))` wrapped; a variable count `(logand n 31)` |
| `s + x` | `(%string-concat s' x')`, each operand by its type: `(or s "null")`, `(%decimal-piece i)`, `(%java-char-string c)`, `(if b "true" "false")`, `(%java-double-string d)`, `(%string o)` |
| `a && b`, `a & b` (boolean) | `(and a b)`; both operands bound first unless the second runs nothing |
| `x op= v`, `x++` | the place's operands bound once (`let*`) when more than a variable; the result cast to the place's type |
| `a * b` (long), `a >>> 3` | `(%mask-signed-field 64 (* a b))`; `(logand (ash a -3) 2305843009213693951)` |
| `f * g` (float), `x % y` (double) | `(%single-float (* f g))`; `(%java-double-rem x y)` |
| `(int) d`, `(byte) i` | `(%java-double-to-int d)`: toward zero, the type's ends outside it; `(- (logand (+ i 128) 255) 128)` |
| `new int[n]`, `{1, 2}`, `xs[i]` | `(make-array n :element-type '(unsigned-byte 32) :initial-element 0)` (each primitive its own, "Numbers"); `(vector ..)` / `:initial-contents`; `(- (logand (+ (aref xs i) 2147483648) 4294967295) 2147483648)` |
| `while`, `for`, `do` | `(while test body.. update..)`; `(block %break ..)` around it and `(block %continue ..)` around the body only when the body has that exit; `do` tests a flag set after the body |
| `for (T x : xs)` | an array: a counter, the length taken once; an `Iterable`: `(let* ((it (iterator xs))) (while (hasNext it) (let ((x (next it))) ..)))`, each call direct or dispatched as any call |
| `return v` | the value in tail position, else `(return-from %fn v)` and a `(block %fn ..)` |
| `switch (n) { case 1 -> a; default -> b; }` | `(cond ((eql n 1) a) (t b))`; a statement's `break` is `(return-from %break nil)` |
| `yield v` | the value in tail position of its arm, else `(return-from %yield v)` |
| `o instanceof Circle c` | `(and (Circle? o) (progn (setq c o) t))`, `c` a `let` of the statement; `if (o instanceof Circle c) a` is `(if (Circle? o) (progn (setq c o) a))` |
| `new Inner()`, a local class | `(Outer.Inner.new outer args.. captured..)` |
| `new int[n][m]` | `(%java-new-array (list n m) kind default)` |
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
process, part of `./mvnw test`. `JavaLoweringTest` (the table as emitted forms, names and constants, the
refusals and the compile errors with their positions;
`theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`; `aProgramOfSeveralFilesIsReadFromItsSourceTree` over
an in-memory `JavaFiles`: the refusals of the source tree, the imports and the access). A
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
array cast no value passes), `DocExamplesTest` (a
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
`a-cleanup-a-return-from-runs-is-outside-every-handler-it-left` (a cleanup's own exit).
