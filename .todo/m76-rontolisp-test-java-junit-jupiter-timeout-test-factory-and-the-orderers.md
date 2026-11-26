# m76. `rontolisp test` of Java files: `@Timeout`, `@TestFactory`, the orderers, a failure's line

Difficulty: High

What the runner (`JavaTests`, `rontolisp.internal.TestRun`) refuses by name today
(`.kb/java-frontend.md`, "Tests of a program"):

- `@Timeout`. Measured under JUnit 6.0.3: SAME_THREAD mode INTERRUPTS the test's thread
  when the time is over (a `Thread.sleep` past it ends with `TimeoutException: t() timed
  out after 20 milliseconds`, singular unit for 1, lowercase); `0` is a
  `PreconditionViolationException` (`timeout duration must be a positive number: 0`). A
  run-to-completion check is not that: the interrupt has to reach a sleeping thread on
  every backend (on wasm threads take turns).
- `@TestFactory` (dynamic tests and containers, their display names in the report).
- `@TestClassOrder`, `MethodOrderer.Random` and orderers of one's own.
- A failure's line has no source position; the interpreter and the JVM could give the
  failing assertion's file and line as an uncaught exception's location line does.

Each behavior is probed under JUnit 6.0.3 first and pinned by the corpus case
(`a-file-of-tests-is-run-and-reported-as-junit-and-assertj-do` of `java-spec.yaml`)
against the oracle on the four backends.
