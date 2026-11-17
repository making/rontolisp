# The EXPERIMENTAL front end for a subset of the Java language (`am.ik.rontolisp.java`)

**Invariant: a Java file is read, typed and LOWERED to the Common Lisp core forms the pipeline
already consumes; no backend learns a Java name; a construct the subset does not have is
refused BY NAME at its position, never lowered as something else.** Status is experimental -- "an
experimental subset of the Java language", no compatibility claim -- and every user surface says
so (`--source-language` help, `doc/*/java/`, `examples/java/README.md`). Provenance and naming
rules: [java-frontend-provenance.md](java-frontend-provenance.md). `--no-gc` is refused by name
(`CompileFrontend.run`). First slice: j68 (2026-11-17); the declarations and statements past
it: j69 (2026-11-17); type parameters, inference, lambdas and boxes: j70 (2026-11-17); the
library: j71 (2026-11-17); what comes after is `.todo/j72` (numbers), `j73` (exceptions),
`j74` (several files, a session), `j76` (threads), `j77`, `j78` and `j79` (what j69, j70 and
j71 left).

## Where it sits

- `java` depends on the AST types and `reader` (`LispReadException`) only (`PackageCycleTest`):
  `JavaLexer` + `JavaParser` + `JavaSyntax` (text -> tree; own lexer and parser, no dependency),
  `JavaClasses` (the class table of one program), `JavaClass` / `JavaType` (members, static
  types), `JavaTypes` (the relations between types: substitution, subtyping, capture,
  inference), `JavaIntrinsics` (the library classes whose values are Lisp values), `JavaLowering`
  (tree -> core forms, typing as it goes), `JavaInitOrder` (when a class's static initialization
  runs), `JavaPatterns` (what a switch's patterns cover), `JavaNames` (spelling), `JavaForms`,
  `Java` (facade).
- Reached ONLY through the seam: `eval/SourceLanguage.JAVA`, `.java` or `--source-language java`
  ([source-language.md](source-language.md)). A program is ONE file lowered whole. No
  `SourceSession`: the REPL and the playground refuse it (`.todo/j74`).
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
  `Boolean` box does now). A boxed `long` is the cons `(number . :long)` ("Boxes" below).
- **`int` is an integer kept in range** by the unconditional mask
  `(- (logand (+ x 2^31) (2^32 - 1)) 2^31)`, `long` the same at 64 bits (a bignum in between:
  `.todo/j72`); `char` an integer whose static type prints it. No declaration is emitted.
- **A class is a `defstruct`**, `extends` its `:include`, a field a slot defaulting by type, a
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
  `%simple-name`, `%lisp-error`, `Class.m(sig)%v`, `Iface%is`.
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
  the condition's `:report` calls: an uncaught one is `Unhandled condition: <toString()>` with
  the location lines of any error. `try`: ONE `handler-case` clause on `error` binding
  `(%java-thrown c)`, a `cond` over the clause types (each its own parameter), `(error c)` again
  when none takes it; `finally` is `unwind-protect`. A Lisp `error` reaches a clause above
  `ArithmeticException` (a `division-by-zero`, message `/ by zero`) or `RuntimeException`
  (anything else) through the generated `%lisp-error`; a clause below lets it pass.
- **A program that throws starts inside `(unwind-protect (main) nil)`**: outside EH mode a wasm
  module reports nothing of an uncaught condition (true of any Lisp program). Measured
  2026-11-17 with every program wrapped: `Sieve.wasm` 10,380 -> 18,170 bytes, `Fib` 2,728 ->
  2,845, no time change; hence only a program holding a `throw` or a failing cast is wrapped. A
  program whose only errors are the run time's stays silent on wasm (`.todo/j73`).
- **`instanceof` / a cast**: a class is its predicate; an interface the generated `Iface%is`
  (the predicates of the topmost classes implementing it, of the library's values that are it,
  and of its lambdas: "Lambdas" below); a library class its own (`simple-string-p`, `integerp`,
  `consp` for a `Long`, `%java-number-p`, `%java-sb-p`). A cast to a type variable
  tests its bound, to a parameterized type its class; a cast only the type arguments tell from
  no cast has no test. `instanceof` of a type the erased program cannot answer is the
  compiler's `X cannot be safely cast to Y`. A failing cast throws `ClassCastException()`.
  A narrowing cast to an array type stays refused: `(signed-byte 32)` upgrades to `T` on every
  backend (measured 2026-11-17: `array-element-type` of an `int[]` is `T` on the interpreter,
  the JVM and wasm; only `double-float` is kept), so an `int[]` is not told from an `Object[]`,
  and no array knows its element type -- the store check is `.todo/j72`'s with the arrays.
- **`Object`'s methods**: a class declaring one is called as any method; through a type none of
  whose classes declares it, the program-wide `%string` / `%equals` / `%hash` test the classes
  declaring one, deepest first, then `%java-to-string` / `-equals` / `-hash-code` answer for
  every other value (identity for a structure: a number kept in an `eq` table from the first
  ask). `getClass()` exists only before `.getName()` / `.getSimpleName()`.
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
  a class around is read through the chain of those slots.
- **A local and an anonymous class are lowered where they are declared**, once a pass, behind a
  scope that marks the class's boundary. A local read across the boundary is a CAPTURE: a slot
  `val$<name>$<depth>` of the class, set where an instance is made from the value the name has
  there (which is the enclosing local class's own slot when the creation stands in one). By
  value, so the local must be effectively final: a local with an initializer that is assigned
  again is refused where a lambda or a class reads it. Rejected: lowering the class's methods as
  closures (a method is a `defun` taking `this`; dispatch and `super` calls name it).
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
  -- no `tagbody`. A local declared in a colon case is the whole switch's: hoisted to one `let`.
  A `null` selector reaches `%java-null-switch` unless a case names `null`. A switch expression
  with no target type is lowered twice: once to find the type its arms share.
- **A pattern's binding is a variable of the statement the pattern stands in**, declared by a
  `let` around the statement and set where the test holds:
  `(and (Circle? o) (progn (setq c o) t))`. Where it is in SCOPE is the language's: `flow`
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
  instance is refused (`early`) but for the store of a field.
- **A default method** is the body `implementation` answers when no class above declares one;
  a dispatcher tests the topmost class inheriting it. A lambda called through a default runs
  the default (`functionp` arm).
- **A variable arity method** fits in a third phase, after the fits without and with boxing.

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
- **Not done** (`.todo/j78`): the arguments of the class around an inner class are no part
  of the inner class's type, so a member typed by them read from OUTSIDE that class is refused
  by name (`inScope`); a type argument is not checked against its parameter's bound.

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

- **A boxed `long` is `(number . :long)`**; no other value of a program is a cons. So an
  `Integer` and a `Long` are told apart by `integerp` / `consp`, by `equals`, `hashCode`,
  `getClass()` and a pattern switch over `Object`, and `equal` -- the `HashMap`'s test -- tells
  a `Long` key from an `Integer` one as `equals` does. Rejected: one value for both (j68;
  `case Integer i` took a `Long`, silently) and a structure in `java.lisp` (the pruner keys a
  bundled structure by name, `.kb/library-defun-pruning.md`; a cons needs nothing).
- **Unboxing tests for `null`**: `(or box (%java-null-unbox))`, then `car` for a `long`,
  `char-code` for a `char`; a literal is not tested. It raises a Lisp error with the text
  `NullPointerException: ..`; the class is `.todo/j73`'s.
- **`==` of two boxes compares the values** (`eql`, `equal` for `Long`): a box is its value.
  The language promises it from -128 to 127 and allows it elsewhere. `==` of two strings:
  "The library" below.
- Measured 2026-11-17, before / after the two changes (a 1,000,000-element list summed 20
  times plus 30,000,000 unboxings in a loop): `List<Integer>` JVM 2.47 / 2.75 s, wasm 9.95 /
  9.60 s; `List<Long>` JVM 9.85 / 10.35 s, wasm 32.90 / 41.14 s (the `long` arithmetic owns
  that program: `.todo/j72`). `Fib`, `WrapInt`, `Sieve`, `WrapLong` are unchanged in time and
  in wasm bytes (2,728 / 2,508 / 10,380 / 4,544).

## The library (2026-11-17, j71)

- **The split.** INTRINSIC: `Object`, `String`, `StringBuilder`/`StringBuffer` (one
  representation, one predicate), `Number` and the five boxes, `Math`, `System`,
  `PrintStream`, `IO`. SOURCE: everything else -- the collections, `Arrays`, `Collections`,
  `Objects`, `Optional`, `Random`, `Comparator`, `java.util.function`, the `Throwable` family,
  `Iterable`, `CharSequence`. Three bridges: a `native` method of the source is a form
  (`JavaIntrinsics.NATIVES`, keyed `class.name(erased parameters)`; the parser takes `native`
  only under `library/`); an intrinsic member written in Java is a static method of
  `rontolisp.internal.Support` reached through `Context.library` (`parseInt`, `addExact`: they
  throw the library's exceptions); an intrinsic class implements a source interface, and gains
  the members naming it, once a program names that interface (`Library.implemented` / `meets`:
  `Comparable`, `CharSequence`, `String.join(.., Iterable)`).
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
  `Vector` extends `ArrayList` and `StringBuffer` is `StringBuilder`'s representation.
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
  printed 43,929 / 56,779; a `HashMap` put and read 37,802 / 57,847. `.todo/j79` holds what is
  left and the leads.

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
- Not the language's: a run-time error inside an eager initialization surfaces before `main`;
  an exception out of a static initializer is not wrapped (`.todo/j73`).

## The lowering table

| Java | core forms |
|---|---|
| `a + b` (int) | `(- (logand (+ (+ a b) 2147483648) 4294967295) 2147483648)`; `/` is `truncate`, `%` `rem` |
| `a << n`, `a >>> n` | `(ash a n)` wrapped; `(ash (logand a mask) (- n))` wrapped; a variable count `(logand n 31)` |
| `s + x` | `(%string-concat s' x')`, each operand by its type: `(or s "null")`, `(%decimal-piece i)`, `(%java-char-string c)`, `(if b "true" "false")`, `(%java-double-string d)`, `(%string o)` |
| `a && b`, `a & b` (boolean) | `(and a b)`; both operands bound first unless the second runs nothing |
| `x op= v`, `x++` | the place's operands bound once (`let*`) when more than a variable; the result cast to the place's type |
| `(int) d` | `(%java-double-to-int d)`: toward zero, the type's ends outside it |
| `new int[n]`, `{1, 2}` | `(make-array n :element-type '(signed-byte 32) :initial-element 0)` (`double-float` for `double`, untyped otherwise); `(vector ..)` / `:initial-contents` |
| `while`, `for`, `do` | `(while test body.. update..)`; `(block %break ..)` around it and `(block %continue ..)` around the body only when the body has that exit; `do` tests a flag set after the body |
| `for (T x : xs)` | an array: a counter, the length taken once; an `Iterable`: `(let* ((it (iterator xs))) (while (hasNext it) (let ((x (next it))) ..)))`, each call direct or dispatched as any call |
| `return v` | the value in tail position, else `(return-from %fn v)` and a `(block %fn ..)` |
| `switch (n) { case 1 -> a; default -> b; }` | `(cond ((eql n 1) a) (t b))`; a statement's `break` is `(return-from %break nil)` |
| `yield v` | the value in tail position of its arm, else `(return-from %yield v)` |
| `o instanceof Circle c` | `(and (Circle? o) (progn (setq c o) t))`, `c` a `let` of the statement |
| `new Inner()`, a local class | `(Outer.Inner.new outer args.. captured..)` |
| `new int[n][m]` | `(%java-new-array (list n m) kind default)` |
| `f(1, 2)` to `f(int...)` | `(f (make-array 2 .. :initial-contents (list 1 2)))` |
| `x -> e` | `(lambda (x) e)`; `(%make-Iface%lambda (lambda (x) e))` where the program can tell the interface |
| `String::length`, `obj::m` | `(lambda (%t0) (length %t0))`; `(let ((%t1 obj)) (lambda (%t0) (m %t1 %t0)))` |
| `Long b = 5L`, `long n = b`, `int i = boxed` | `(cons 5 :long)`; `(car (or b (%java-null-unbox)))`; `(or boxed (%java-null-unbox))` |
| `above(T o)` with `T extends Comparable<T>` | `(defun Box.above(Comparable) (this o) ..)`; `held.compareTo(o)` is `(Comparable.compareTo(Object)%v held o)` |
| `xs.get(i)` on a `List` | `(java.util.ArrayList.get(int) xs i)` while `ArrayList` is the one list the program makes, else `(java.util.List.get(int)%v xs i)` |
| `a == b` (strings) | `(eq a b)`; every string constant of such a program is a global `%string<n>` set to `(%java-intern "text")` before `main` |
| `Arrays.sort(ns)`, `Integer.parseInt(s)` | `(java.util.Arrays.sort(int[]) ns)` whose body is the native `(%java-sort-numbers a 0 (length a))`; `(rontolisp.internal.Support.parseInt(String,int) s 10)` |

## Measured (2026-11-17)

Linux x86-64 (64 threads), GraalVM JDK 25.0.5, wasmtime 49.0.0, best of three; javac's class /
the spike's lowering (JVM, precompiled wasm) / this front end's:

| program | javac | spike | front end | `.class` / `.wasm` bytes (spike -> front end) |
|---|---|---|---|---|
| `Fib` | 0.14 | 0.21 / 0.18 | 0.19 / 0.18 | 25,978 / 5,194 -> 20,749 / 2,728 |
| `WrapInt` | 0.18 | 0.23 / 0.11 | 0.21 / 0.11 | 28,119 / 5,017 -> 21,998 / 2,508 |
| `Sieve` | 0.21 | 1.20 / 0.57 | 1.22 / 0.61 | 41,446 / 11,136 -> 35,936 / 10,380 |
| `WrapLong` | 0.12 | 3.57 / 7.74 | 3.46 / 7.46 | 27,604 / 7,049 -> 21,519 / 4,544 |

The programs are `.todo/artefacts/j68-java-front-end-spike/programs/`. The corpus (47 cases,
5 standalone, 22 refused, 2026-11-17) and `examples/java/Orders.java` print what the JDK
prints on four backends. After j71 the four programs are byte-identical but `Sieve`
(10,380 -> 10,497: `Math.sqrt` is `%strict-math`).

## What a compiler reports and this does not, stated in `doc/*/java/deviations.md`

Checked: `private` (the unit is the top-level class), assignment to a `final` field or local,
effectively final captures, a missing `return` and an unreachable statement where it is SURE
(`completes` / `exits`), and definite assignment through `if`, `while` and `for`. The rule for
each is to refuse only what a compiler refuses: where the lowering does not follow the branches
(`switch`, `try`, `do`, `&&`, `?:`) an assignment on any path counts. Not checked: checked
exceptions; a blank local assigned twice and then captured (`.todo/j77`, with what is still
refused by name); a type argument outside its parameter's bound and the unchecked warnings
(`.todo/j78`). A value read through a type argument is trusted (no cast where it is used),
and a store into an array is not checked. A `null` receiver of a direct call
runs the body. The run time's errors keep their Lisp class and text.

## Tests

`JavaSpecE2eTest` over `java-spec.yaml`: the cases concatenated into one file behind a class
calling each case's `main`, run under the JDK running the test (the oracle leg: an expectation
that is not the JDK's fails there), the interpreter, the JVM, wasm and the component; a
`standalone:` case is a file of its own (the launch rules, an uncaught exception); a
`refused:` case is the scaffold with the case's statements, which the JDK has to refuse with
`error` at that line and the front end at the same line with a message starting the same. In
process, part of `./mvnw test`. `JavaLoweringTest` (the table as emitted forms, names and constants, the
refusals and the compile errors with their positions;
`theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches`). `SourceLanguageTest#javaClaimsItsExtension`,
`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot`,
`SchemeLibraryTest#aCommonLispProgramReadsNoFrontEndsLibrary` (the gate),
`UncaughtReportParityTest#aFrontEndsSourceIsLocatedTheSameWay`,
`WasmReportLocationsTest#aFrontEndsProgramNamesTheFunctionHoldingTheForm`, `DocExamplesTest` (a
` ```java ` fence is a whole program, its output the plain block after it), `ExamplesE2eTest`
(`examples/java/Orders.java`).
