# j68. Java front end: the first slice, behind the source-language seam

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".
This item sets the wording of every user surface and writes the first library classes
and the first spec cases.

## The first target (decided 2026-11-17)

**The language is Java 25; the library is a small core.** Every item of this family says
what of it is inside this target and what comes after. The target says what is OWED, not
what is refused: a construct or a class outside it that is cheap to lower and useful is
taken when a program asks for it.

- **Language: Java SE 25, final features only** -- generics, enums, annotations (parsed and
  dropped), lambdas and method references, `var`, text blocks, switch expressions, records,
  sealed types, patterns for `instanceof` and `switch`, record patterns, the unnamed `_`,
  statements ahead of `super(..)`/`this(..)`, module imports, a compact source file with
  an instance `main`. 25 over 21 costs those last four and `IO`, each small, and the
  compact source file is the shortest program there is (the spike lowered it:
  `programs/Compact.java`). No preview feature. The oracle is a JDK 25.
- **Library: what the language itself needs, plus a core the size of a small-device
  profile, in today's classes** (the list is j71's). Not the platform: no reflection, no
  class loading by name, no streams, no `java.time`, no networking, no `java.nio`.
- **Outside the first target**: threads and `synchronized` (j76), the wider library
  (j71, "After"), the session and the other tools (j74, "After").

A fifth source language: `.java` (and `--source-language java`), EXPERIMENTAL like Scheme,
Clojure and Starlark, lowered to the core forms so no backend learns a Java name
(`.kb/source-language.md`, `.kb/starlark-frontend.md` for the nearest precedent).

## What the spike measured (2026-11-17)

`.todo/artefacts/j68-java-front-end-spike/` (README there: how to run, the numbers).
A 1,977-line single file -- lexer, parser and tree about 950, the lowering about 1,000 --
lowers three programs that print what the JDK prints on all four backends: classes with
three levels of `extends`, interfaces, abstract methods, `super`/`this` calls, static
fields, overloads resolved by static type, `int` overflow, `long`, `char` arithmetic,
arrays, `try`/`catch`/`finally` with user exceptions, lambdas, `ArrayList`, `HashMap`,
`StringBuilder`, a compact source file. `fib(35)` 0.09 s on the JVM and 0.08 s on wasm,
0.07 s as javac's class; an 8,420-byte `.wasm`. So the language is reachable; the work is its width.

## The decisions the spike took, to keep or to overturn with a measurement

- **Own lexer and parser, no dependency.** The parse was the small half; what took the
  work is what no parser library gives: the class table, static types, overloads, the
  dispatch sets. Rejected: javaparser-core (1.49 MB jar, 3.28.2) in the executable jar,
  the native image and the web playground, against the no-dependency rule, for a tree only.
- **Static types pick the form.** `+` is `concatenate`, a wrapped integer add or a double
  add by its operands' types; a conversion (`int` to `double`, a primitive to its text) is
  made where the types say, never tested when it runs.
- **`int` is an integer kept in range** by an unconditional mask, `(- (logand (+ x 2^31)
  (2^32 - 1)) 2^31)`, a shape the backends' integer paths already compile raw; testing the
  range first cost 4x (the artefact README, "The wrap's shape is the speed"). No type
  declaration is emitted: none measured faster. `boolean` is `T`/`NIL`, `char` an integer whose static type prints
  it as a character, `null` is `NIL` (a `Boolean` box holding `false` is then `null`: decide
  it here, `:null` or a boxed false).
- **A class is a `defstruct`**, `extends` its `:include`, a field its slot, an instance
  method `(defun |C.m(int,String)| (this ..))`, a static one the same without `this`, a
  static field a global. The signature is in the name, so overloads never meet.
- **The program is closed, so a call is direct when it can be**: one implementation under
  the receiver's static type is a plain call; more are one `cond` over the structure
  predicates, deepest class first, made once per (static type, signature). An interface
  with one abstract method and no class is `funcall` of a `lambda`.
- **An exception is one condition type holding the object**; `catch` is a `handler-case`
  arm testing the object's class and signalling again when no clause takes it; `finally` is
  `unwind-protect`. A Lisp `error` reaches a `catch` of its Java counterpart's supertype.
- **Library classes are Java source lowered with the program** where they need nothing
  (the spike's `Throwable` family), intrinsics where a Lisp value is the representation
  (`String`, `StringBuilder`, `ArrayList`, `HashMap`).
- **The JDK is the oracle**: a case's expected output is what `java File.java` prints.

## To make

1. Package `am.ik.rontolisp.java` (depends on the AST types and `reader` only,
   `PackageCycleTest`): lexer, parser, tree, class table and attribution, lowering to
   `LispVal` (not text), positions on every refusal (`LispReadException`).
2. The seam: `SourceLanguage.JAVA`, `.java`, `forFile` / `isSourceFile` / `parse`;
   `--no-gc` refused by name; the `--source-language` help and the error text.
3. The run-time half `eval/java.lisp` spliced by a `JavaLibrary` the `StarlarkLibrary` way
   (name gate `%JAVA-`), pruned when unreachable; the Java-source classes shipped in the
   jar and lowered behind the seam.
4. The subset of the spike, each construct either lowered or refused by name with its
   position -- never lowered wrong. The spike's shortcuts to close inside this item:
   a compound assignment to a place with side effects, catch parameters with different
   names in one `try`, `instanceof` of an interface, a reference cast that checks,
   `Object` methods on a class that declares none, an uninitialised local.
5. Tests: a lowering table test, and `java-spec.yaml` + `JavaSpecE2eTest` on the four
   backends with expectations taken from the JDK. `.kb/java-frontend.md`, `doc/en/java/`
   + `doc/ja/java/`, an example under `examples/java/`.

Entry: a class with `static void main(String[])` or any `main` the launch protocol
accepts, or a compact source file with an instance `main`. One file; several files are j74.
