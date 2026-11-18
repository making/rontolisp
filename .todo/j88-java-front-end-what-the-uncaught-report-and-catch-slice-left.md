# j88. Java front end: what the uncaught report and catch slice (j82) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a message is
matched by running a program under a JDK.

State after j82 (2026-11-17, `.kb/java-frontend.md`, "Exceptions"): the entry is
`(%reporting-uncaught #'%java-uncaught-line (%host-exit 1) ..)`, the pipeline's own report
with a JDK's line in it, so no handler holds the condition (`Fib.wasm` 41,656 -> 2,868 B); a
`catch` is a `handler-case` clause of type `(satisfies %catch<n>)` and nothing is signalled
again (a `try` in a loop: 183,852 -> 64,019 B of wasm). Raising the host's failures at the
site was measured and not made (an index test: 9 ns an array access on wasm).

- **EH mode slows string reads on wasm**: the `charAt` loop (26 letters, 10^6 times) is
  0.27 / 1.38 s (JVM / wasm), 0.76 s on wasm outside EH mode (j73). What `(char s i)` pays in
  EH mode is `WasmCharCompiler.compileChar`'s `_idx_chk` call, two stores of the operator
  register around the call (`WasmOperandTypes.emitCall`) and `_str_char_ref`'s
  `_charvec_p` and string tests. A Java `String` is the host's immutable string and the
  lowering has tested the index already, so the read could be `_str_char_at` alone.
  `(the simple-string s)` cannot ask for that: a `make-string` buffer is a `simple-string` too
  and `_str_char_at` traps on one. The lead: an internal read of an immutable string's
  character code that checks nothing (a macro to `(char-code (char s i))` on the interpreter
  and the JVM, `_str_char_at` on wasm; `.kb/adding-primitives.md`), measured against the
  `charAt` loop before it is kept.
- **A program that starts threads still holds its condition**: `%java-thread-main` and a
  thread's body are a `handler-case` binding the condition (`%java-thrown-text`), so such a
  program pays what j73's entry cost every program. `%reporting-uncaught` covers the entry
  only; a thread's line needs the same reading of a report where the thread ends.
- **A program that throws one exception is 60,901 B of wasm** (a loop calling a method with
  one `throw new IllegalStateException(..)`, no `try`; `Fib`, which throws nothing, is 2,868 B). What the
  bytes are -- `Throwable`'s source, the `define-condition`, the report -- is not measured.
- **`MatchException` and the run time's other errors are the library's classes only at a
  `catch`** (`%lisp-error`, by the table's class name); j87 left them here.
- **`StackOverflowError`** where a backend can (`.kb/interpreter-stack.md`); it waits for a
  program that needs it.
- **`ExceptionInInitializerError`** around an exception out of a static initializer, and
  `NoClassDefFoundError` at a later use of that class (stated in `deviations.md`).
- **Checked exceptions** are not checked (`throws`, `catch` of one the `try` cannot throw);
  stated in `deviations.md`.
- **A `NullPointerException` the run time raises has no message**; a JDK's names the null
  expression by its class file's names (`"<local4>"` under the source launcher). Matching
  `javac -g`'s text needs the expression at the raising site.
- **The location line under an uncaught exception names a statement, one frame**
  (`  at Uncaught.java:4 in Uncaught.check(int,int)`): the pipeline's line, not a JDK's
  `\tat Uncaught.check(Uncaught.java:4)` frames. An expression inside a statement is not
  located (only statements, calls and a tail `return`'s value are: `JavaLowering.located`).
