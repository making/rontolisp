# k17. Java front end: what is left of the exceptions' run time (from j88, j99, k06)

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a message is
matched by running a program under a JDK.

Carried from k06 unchanged (k06 did the flow's order; `Object.clone()` is `.todo/k16`).
The state: `.kb/java-frontend.md`, "Exceptions".

- **An error the run time raises out of a static initialization is not wrapped** and its
  class stays usable (stated in `deviations.md`). Measured in j99 (`.kb/java-frontend.md`,
  "Exceptions", the static initialization entry; the j99 artefacts' `init/`): marking the
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
