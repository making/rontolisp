# k06. Java front end: what the checked exceptions slice (j99) left

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a message is
matched by running a program under a JDK.

State after j99 (2026-11-18, `.kb/java-frontend.md`, "Exceptions" and "The flow"): checked
exceptions are checked as javac 25 checks them -- the clauses kept and compared with the
running JDK's by reflection, an override throwing more, a method reference's thrown
types, and in the flow what a body throws against what catches or declares it, a catch
clause its `try` cannot reach (an error, or the warning), a catch parameter thrown again,
a type parameter of a `throws` clause found from a lambda's body. The probes and the
script comparing a program with javac:
`.todo/artefacts/j99-java-front-end-what-the-charat-initializer-and-causes-slice-left/`.
A cleanup leaving by an exit of its own compiles on the JVM and wasm
(`.kb/error-handling.md`, Phase 1).

## Of the checked exceptions

- **A local class's flow is checked where the class is lowered**, ahead of an earlier
  method of the class around it; javac checks it with the method declaring it. One error
  is reported, so it shows only in a program with two (the artefacts' `refused/V92`).
  The same holds for the reachability and assignment errors since j77. Lead: keep the
  local classes' problems and take them into the walk where `JavaFlow` meets the
  declaration.
- **`Object.clone()` and `finalize()`** are no members here, so their clauses
  (`CloneNotSupportedException`, `Throwable`) are met by no program. With `clone()`:
  `Cloneable`, the shallow copy of a structure, and the array arm that exists already.
- **A type variable thrown** is named without the `where E is a type-variable` lines javac
  adds under the message (`JavaWarnings.where` writes them for the warnings).

## Of the list j88 left, not done

- **An error the run time raises out of a static initialization is not wrapped** and its
  class stays usable (stated in `deviations.md`). Measured in j99 (`.kb/java-frontend.md`,
  "Exceptions", the static initialization entry; the artefacts' `init/`): marking the
  class with a `finally` is free (18,744 -> 18,828 B of wasm), a later use THROWING
  `NoClassDefFoundError` costs a program that throws nothing 31 KB. What is left to try:
  the later use raising the run time's text (`%java-raise`, an object only where a
  `catch` makes one), which gives `NoClassDefFoundError: Could not initialize class C`
  with no cause after a first error that is still the host's own. Three shapes of the
  initialization would carry the mark (plain, under threads, one that can throw).
- **A program that throws one exception is 46,765 B of wasm** (`Fib` 2,868 B). 25 KB of
  it is the pipeline's, for any program that can build a condition with a `:report`: the
  print-object seam (`%pos-walk` and its helpers, 8.8 KB), the printer (7.7 KB) and the
  report through a string stream (8.5 KB). Leads, each the pipeline's and each measured
  before it is kept: `%pos-walk` only where a program prints a cons or a vector that can
  hold an instance; a report that is one string asked without a stream. Also: the prelude
  keeps a function a program names in ANY position (`LispPreludeLibrary.process`,
  `symbolNames`): `'character` and `'bit` still keep two small functions.
- **`StackOverflowError`** where a backend can (`.kb/interpreter-stack.md`); it waits for a
  program that needs it.
- **A `NullPointerException` the run time raises has no message**; a JDK's names the null
  expression by its class file's names (`"<local4>"` under the source launcher). Matching
  `javac -g`'s text needs the expression at the raising site.
- **The location line under an uncaught exception names a statement, one frame**
  (`  at Uncaught.java:4 in Uncaught.check(int,int)`), after the causes: the pipeline's
  line, not a JDK's `\tat Uncaught.check(Uncaught.java:4)` frames under each exception. An
  `ExceptionInInitializerError` out of the class the program starts at has no line (the
  wrapping `throw` is generated, at no position); a thread's report has none. An
  expression inside a statement is not located (`JavaLowering.located`).
- **Two threads tracing exceptions at once read each other's numbers**
  (`Throwable.traces`): a circular reference could be written twice, or a line as one. A
  set of identities costs each program that reports an exception 2.4 KB more (measured:
  `.kb/java-frontend.md`, "Exceptions").
