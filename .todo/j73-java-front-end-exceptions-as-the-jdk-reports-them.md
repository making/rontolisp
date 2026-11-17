# j73. Java front end: exceptions as the JDK reports them

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".
Exception messages are matched by running programs under a JDK, not by reading its
source.

**First target (j68): all of this item** but the stack overflow mapping, which waits for
a program that needs it.

After j68. The spike (`.todo/artefacts/j68-java-front-end-spike/`) throws one condition
type holding the Java object, catches by `handler-case` plus a class test, and turns a
Lisp `division-by-zero` into an `ArithmeticException` only inside a `catch` that could
take one. Left:

- Every error the run time raises for a Java construct is the Java exception a program
  can catch, with the JDK's message: `NullPointerException` (a field, a call, an array, an
  unboxing of `null`), `ArrayIndexOutOfBoundsException`, `ClassCastException`,
  `NegativeArraySizeException`, `NumberFormatException`, `ArithmeticException`,
  `StackOverflowError` where a backend can (`.kb/interpreter-stack.md`). Decide where the
  conversion sits: at the raising site (a Java-aware accessor) or at the `catch` (the
  spike's), with what each costs a field read.
- An uncaught exception: `Exception in thread "main" java.lang.IllegalStateException: msg`
  on standard error and exit status 1 on all four (`.kb/error-handling.md`); a stack trace
  is not promised -- say so in the doc.
- `try`-with-resources and suppressed exceptions, multi-catch, a `catch` parameter per
  clause, `return`/`break`/`continue` leaving through `finally`, a `finally` that returns,
  rethrow, causes (`getCause`, `initCause`), `getMessage`/`toString`/`printStackTrace`.
- Checked exceptions: the `throws` clauses are checked, as a compiler does, or the doc
  says they are not.
- A Lisp caller sees a Java exception as a condition with a readable report; a Java
  `catch (Exception e)` sees a Lisp `error` as some `RuntimeException` (the spike's
  `%jlisp-error`): fix the mapping in one table.
- What a non-local exit costs: `try` compiles in EH mode on wasm
  (`.kb/running-backends.md`); measure a loop with a `try` inside it.
