# j82. Java front end: what the exceptions slice (j73) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a message is
matched by running a program under a JDK.

State after j73 (2026-11-17, `.kb/java-frontend.md`, "Exceptions"): the run time raises text
errors, one table (`%java-exception-of`) makes them the program's exceptions at a `catch`, and
every program runs inside a `handler-case` writing a JDK's uncaught line and exiting with 1.

- **The size the entry's handler costs.** A handler that holds its condition makes every
  signal site build one: `Fib.wasm` 2,728 -> 41,656 B, `Sieve.wasm` 10,819 -> 48,675 B,
  `Fib.class` 20,743 -> 61,620 B. The lead: the pipeline's own uncaught report already renders
  a condition's text lazily in the wasm pad ("Unhandled condition:", measured ~8 KB on `Sieve`
  in j68). A program-level choice of that line's prefix and of exit status 1 (interpreter
  `RontoLispCli.runReporting`, `JvmUncaughtHandler`, `WasmUncaughtReportCompiler`) would let a
  Java program keep the pipeline's report -- and its location lines -- if the host errors it
  maps (`division-by-zero`, a `type-error` over NIL or an index) became text errors where they
  are raised. Measure what those raising-site tests cost before choosing: a direct call's
  `(or x (%java-npe))` measured free, `charAt`'s index test a quarter of a string loop on wasm.
- **EH mode slows string reads on wasm**: `_str_char_ref` tests `stringp` in EH mode, which
  every Java program now is; a loop over a string's characters 0.76 -> 1.18 s from that alone.
  A statically typed program never needs the test.
- **A `catch` that takes nothing re-signals with `(error c)`**, a computed datum: the condition
  narrowing bails and a program with any `try`/`catch` carries ~90 KB more wasm (a `try` in a
  loop: 177,215 B against 55,702 B for the same program without it, since j68). Declining
  without re-signalling (a `handler-bind` whose handler exits only for a clause that takes the
  object) avoids the datum.
- **`StackOverflowError`** where a backend can (`.kb/interpreter-stack.md`); it waits for a
  program that needs it.
- **`ExceptionInInitializerError`** around an exception out of a static initializer, and
  `NoClassDefFoundError` at a later use of that class (stated in `deviations.md`).
- **Checked exceptions** are not checked (`throws`, `catch` of one the `try` cannot throw);
  stated in `deviations.md`.
- **A `NullPointerException` the run time raises has no message**; a JDK's names the null
  expression by its class file's names (`"<local4>"` under the source launcher). Matching
  `javac -g`'s text needs the expression at the raising site.
