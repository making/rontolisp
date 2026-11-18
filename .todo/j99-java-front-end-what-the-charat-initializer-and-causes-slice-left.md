# j99. Java front end: what the charAt, initializer and causes slice (j88) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a message is
matched by running a program under a JDK.

State after j88 (2026-11-18, `.kb/java-frontend.md`, "Exceptions"): `charAt` is one read
(`%string-code`; the loop over 26 letters 1.25 -> 0.61 s on wasm); a static initialization
that throws an object ends in `ExceptionInInitializerError` and its class in
`NoClassDefFoundError` at every later use; what nothing caught is reported with its causes
and suppressed exceptions, a thread's too; a program that throws one exception is 60,903 ->
46,765 B of wasm. Holding no condition in a program that starts threads was measured (3.5
to 6%) and not made.

- **Checked exceptions are not checked** (`throws`, a `catch` of one its `try` cannot
  throw, an override throwing more; stated in `deviations.md`). An item of its own size.
  What it needs: the `throws` clause kept on `JavaClass.Method` (the parser reads it); the
  thrown types of each statement top-down -- a frame per `try` block collecting
  `(type, position)`, popped into the frame around it less what a clause takes, the root
  frame checked against the method's, constructor's or lambda's function type's clause --
  with JLS 11.2.2's rethrow of an effectively final catch parameter, the implicit
  `close()` of a resource, an implicit `super()`, field initializers against every
  constructor, a generic `throws E` through inference (unconstrained: `RuntimeException`).
  The intrinsics need their clauses (`Object.wait`, `clone`, `String.getBytes(String)`,
  `new String(byte[], String)`). **The risk is a valid program refused**: the library's
  source declares what it throws, a JDK compiles it, but nothing compares its clauses with
  the API's -- so first a test asking a running JDK's reflection for the exception types
  of every member the library declares (use, not copying), then the check. javac reports
  these after every error of typing: ask a JDK which error a program with both gets.
- **An error the run time raises out of a static initialization is not wrapped** and its
  class stays usable (stated in `deviations.md`). A clause taking it holds the condition
  and brings the classes of `RAISED` into every program with a lazy class (the j73 entry's
  cost). Leads: mark the class unusable with an `unwind-protect` and no handler (a host
  error then still reports as itself, but the later use throws `NoClassDefFoundError`),
  measured on a program that throws nothing; an eager class's failure still surfaces
  before `main`.
- **A program that throws one exception is 46,765 B of wasm** (`Fib` 2,868 B). 25 KB of
  it is the pipeline's, for any program that can build a condition with a `:report`: the
  print-object seam (`%pos-walk` and its helpers, 8.8 KB), the printer (7.7 KB) and the
  report through a string stream (8.5 KB). Leads, each the pipeline's and each measured
  before it is kept: `%pos-walk` only where a program prints a cons or a vector that can
  hold an instance; a report that is one string asked without a stream. Also: the prelude
  keeps a function a program names in ANY position (`LispPreludeLibrary.process`,
  `symbolNames`): a variable named `describe` kept 10.5 KB here, and `'character` and
  `'bit` still keep two small functions.
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
