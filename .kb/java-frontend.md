# The EXPERIMENTAL front end for a subset of the Java language (`am.ik.rontolisp.java`)

**Invariant: a Java file is read, typed and LOWERED to the Common Lisp core forms the pipeline
already consumes; no backend learns a Java name; a construct the subset does not have is
refused BY NAME at its position, never lowered as something else.** Status is experimental -- "an
experimental subset of the Java language", no compatibility claim -- and every user surface says
so (`--source-language` help, `doc/*/java/`, `examples/java/README.md`). Provenance and naming
rules: [java-frontend-provenance.md](java-frontend-provenance.md). `--no-gc` is refused by name
(`CompileFrontend.run`). First slice: `.todo/j68` (2026-11-17); what comes after is `.todo/j69`
(declarations, statements), `j70` (generics, boxing, lambdas), `j71` (library), `j72` (numbers),
`j73` (exceptions), `j74` (several files, a session), `j76` (threads).

## Where it sits

- `java` depends on the AST types and `reader` (`LispReadException`) only (`PackageCycleTest`):
  `JavaLexer` + `JavaParser` + `JavaSyntax` (text -> tree; own lexer and parser, no dependency),
  `JavaClasses` (the class table of one program), `JavaClass` / `JavaType` (members, static
  types), `JavaIntrinsics` (the library classes whose values are Lisp values), `JavaLowering`
  (tree -> core forms, typing as it goes), `JavaNames` (spelling), `JavaForms`, `Java` (facade).
- Reached ONLY through the seam: `eval/SourceLanguage.JAVA`, `.java` or `--source-language java`
  ([source-language.md](source-language.md)). A program is ONE file lowered whole. No
  `SourceSession`: the REPL and the playground refuse it (`.todo/j74`).
- The run-time half is `eval/java.lisp`, spliced by `eval/JavaLibrary` the `StarlarkLibrary` way:
  the interpreter loads it on the first resolution of a `rontolisp::%java-` FUNCTION,
  `CompileFrontend.expand` splices it inside `StarlarkLibrary.process`, `LibraryDefunPruner`
  drops what stays unreachable. Name gate: a member starting `%JAVA-`.
- **Library classes are of two kinds.** One whose representation is a Lisp value is an
  INTRINSIC (`JavaIntrinsics`): its members are emitters of forms, resolved by the same overload
  resolution as a program's methods. Any other is JAVA SOURCE in the jar
  (`src/main/resources/am/ik/rontolisp/java/library/java/lang/Throwable.java`, one class a file,
  found by qualified name), parsed and lowered with the program naming it, in the subset itself.
  `resource-config.json` carries both for the native image.

## The decisions (2026-11-17)

- **Static types pick the form.** Every expression lowers to `(form, type, constant)`
  (`JavaLowering.Typed`); a conversion (widening, boxing, a number's text) is made where the
  types say, never tested at run time.
- **`null` is `NIL`; `boolean` is `T`/`NIL`; no box is `null`.** A boxed number is the number, a
  boxed `char` a Lisp CHARACTER (so `Object o = 'c'` prints `c`), a boxed `true` `T`, a boxed
  `false` `:false` -- so `Map<String, Boolean>.get` tells a stored false from a missing key.
  Rejected: `null` as `:null` (every reference test and every slot default pays, where only a
  `Boolean` box does now). Left to `.todo/j70`: a `Long` box and an `Integer` box of one number
  are one value (`equals` true, `getClass()` says `Integer`).
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
  first, made once per (static type, signature), ending in `%java-no-receiver`. A functional
  interface's dispatcher tests `functionp` first; one no class implements is `funcall`. `super.m()`
  and a `private` method are direct.
- **Overloads**: the candidates the arguments fit with no boxing, else with it; among them the
  one whose parameters convert to every other's; none such is `reference to .. is ambiguous`.
  A lambda and a diamond wait for the chosen parameter's type.
- **Constants** are folded as the language computes them (`fold`), so a constant variable
  (`static final`, primitive or `String`, constant initializer) is its value at every read --
  whatever the order of the classes -- and `char c = 'a' + 1` is a `char`.
- **Static initialization is eager**, class by class in table order, before `main`; the language
  initializes at first use. So an initializer may read constants and the static fields above it
  in its class and run no program code; any other is refused by name (`.todo/j69` decides the
  order).
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
  (the predicates of the topmost classes implementing it, `functionp` when EVERY lambda of the
  program implements it); `String` is `simple-string-p` (a `StringBuilder` is `stringp`). Some
  lambdas implementing it and others not is refused: a lambda does not record its interface
  (`.todo/j70`). A failing cast throws `ClassCastException()`.
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
  from the start when a pass added a class.

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
| `for (T x : xs)` | a counter over the vector, the length taken once for an array |
| `return v` | the value in tail position, else `(return-from %fn v)` and a `(block %fn ..)` |
| `x -> e` | `(lambda (x) e)` |
| `List.get(i)`, `Map.put(k, v)` | `(%java-list-get xs i)` (aref reads past a fill pointer); `(setf (gethash k m) v)` as a statement, `(%java-map-put m k v)` for its value |

## Measured (2026-11-17)

Linux x86-64 (64 threads), GraalVM JDK 25.0.5, wasmtime 49.0.0, best of three; javac's class /
the spike's lowering (JVM, precompiled wasm) / this front end's:

| program | javac | spike | front end | `.class` / `.wasm` bytes (spike -> front end) |
|---|---|---|---|---|
| `Fib` | 0.14 | 0.21 / 0.18 | 0.19 / 0.18 | 25,978 / 5,194 -> 20,749 / 2,728 |
| `WrapInt` | 0.18 | 0.23 / 0.11 | 0.21 / 0.11 | 28,119 / 5,017 -> 21,998 / 2,508 |
| `Sieve` | 0.21 | 1.20 / 0.57 | 1.22 / 0.61 | 41,446 / 11,136 -> 35,936 / 10,380 |
| `WrapLong` | 0.12 | 3.57 / 7.74 | 3.46 / 7.46 | 27,604 / 7,049 -> 21,519 / 4,544 |

The programs are `.todo/artefacts/j68-java-front-end-spike/programs/`. The corpus (23 cases, 1,120
lines) and `examples/java/Orders.java` print what the JDK prints on four backends.

## Not checked, stated in `doc/*/java/deviations.md`

Definite assignment, unreachable code, a missing `return`, checked exceptions, access modifiers,
`final`. A `null` receiver of a direct call runs the body. The run time's errors keep their Lisp
class and text.

## Tests

`JavaSpecE2eTest` over `java-spec.yaml`: the cases concatenated into one file behind a class
calling each case's `main`, run under the JDK running the test (the oracle leg: an expectation
that is not the JDK's fails there), the interpreter, the JVM, wasm and the component; a
`standalone:` case is a file of its own (the launch rules, an uncaught exception). In process,
part of `./mvnw test`. `JavaLoweringTest` (the table as emitted forms, names and constants, the
refusals and the compile errors with their positions). `SourceLanguageTest#javaClaimsItsExtension`,
`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot`,
`SchemeLibraryTest#aCommonLispProgramReadsNoFrontEndsLibrary` (the gate),
`UncaughtReportParityTest#aFrontEndsSourceIsLocatedTheSameWay`,
`WasmReportLocationsTest#aFrontEndsProgramNamesTheFunctionHoldingTheForm`, `DocExamplesTest` (a
` ```java ` fence is a whole program, its output the plain block after it), `ExamplesE2eTest`
(`examples/java/Orders.java`).
