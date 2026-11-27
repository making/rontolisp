# n04. `rontolisp test` of Java files: a failed test's file and line

Difficulty: High

A failure's lines in the report (`rontolisp.internal.TestRun`) carry no source position
(`.kb/java-frontend.md`, "Tests of a program"). Measured 2026-11-27, which the plan has to
start from:

- The uncaught report's location line is the innermost located form, so it is the
  program's line for a check of AssertJ (the library's part, located in no file:
  `Uncaught.java:6 in Uncaught.helper(int)`) and JUnit's own source for an assertion of
  JUnit's, compiled from the source path (`junit-jupiter-api-6.0.3-sources.jar!/org/junit/
  jupiter/api/AssertionFailureBuilder.java:139 in ...buildAndThrow()`). What a report wants
  is the innermost line of the TEST'S files, as surefire's summary names it
  (`CartTest.aLine:19`).
- A CAUGHT exception has a position on no backend: the interpreter's `ConditionTrace` keeps
  only where the condition was signaled and is reached from the uncaught report alone; the
  JVM's site ids are on the host exception's stack trace, read by `_where` at the entry;
  wasm has frames only under `--report-locations`.

Two designs, to be measured against each other before either is made:

1. **From where it was thrown**: a form answering a caught condition's frames (the
   interpreter's trace keeping every located frame it passes, the JVM's read off the stack
   trace, none on wasm), reached from Java's `catch` in `TestRun`. Exact line of the call,
   two backends, a new form on four.
2. **From a mark the test's files set**: under `javaTests` the lowering stores the file
   and line of each statement of the run's own files, the generated code reads the mark
   where it catches what a test threw. Four backends, no new form; the line is the
   statement's first, and a `finally` or a `catch` that throws again in a test moves the
   mark unless the lowering restores it. Costs to measure: a store a statement, the size.

Either way the report differs from the JUnit oracle's by these lines: the corpus case's
`stdout:` then needs a rule for the JDK leg (the oracle can name the line of a frame, not
of a statement).
