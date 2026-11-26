# m61. `rontolisp test` of Java files: what m33 left of JUnit Jupiter and the runner

Difficulty: Medium

m33 added the interfaces' tests and lifecycle methods, `TestInfo`, `@RepeatedTest` and
`RepetitionInfo`, `@TestInstance(PER_CLASS)`, `@TestMethodOrder` (`OrderAnnotation`,
`MethodName`, `DisplayName`) with `@Order`, `@Tag`/`@Tags` and a constructor's parameters
(`.kb/java-frontend.md`, "Tests of a program", "Past one instance a test"). Left, each
refused by name today:

- `@Timeout`. Measured under JUnit 6.0.3: SAME_THREAD mode INTERRUPTS the test's thread
  when the time is over (a `Thread.sleep` past it ends with `TimeoutException: t() timed
  out after 20 milliseconds`, singular unit for 1, lowercase); `0` is a
  `PreconditionViolationException` (`timeout duration must be a positive number: 0`). A
  run-to-completion check is not that: the interrupt has to reach a sleeping thread on
  every backend (on wasm threads take turns).
- `@TestFactory`, `@TestClassOrder`, `MethodOrderer.Random` and orderers of one's own.
- `@CsvFileSource`, `@ArgumentsSource`, `@FieldSource`, the elements of the sources not read
  (`classes` of `@ValueSource`, `from`/`to` of `@EnumSource`), a composed annotation.
- The implicit conversions past the primitive types, their boxes and enums (`Path`, `URI`,
  `UUID`, `BigDecimal`, the dates).
- A display name of a parameterized run (`[1] "1", "x"` for CSV text: JUnit quotes strings),
  and with it a `TestInfo` of a parameterized test.
- `cleanup` and `factory` of `@TempDir`; a constructor `@TempDir` under `PER_CLASS` or of a
  repeated test with a failure threshold (unprobed, refused).
- An annotation of an inherited test is written into the holder of the class that runs it,
  under THAT file's imports: an element naming what only the superclass's file imports is
  refused. A holder per declaring file would mend it.
- A failure's line has no source position; the interpreter and the JVM could give the
  failing assertion's file and line as an uncaught exception's location line does.
- The tree's six suites that do not run stop at the library or the host, not at the runner
  (measured, `.kb/java-frontend.md`, "The tree's suites that run"). The one in reach is
  `KeyValueSessionStoreTest`: `KeyValueStore.Bucket` (a nested interface of a source-path
  `@WitImport` interface) is "cannot find symbol" from the test file though the module's
  own `KeyValueSessionStore` names it; then `usingRecursiveComparison` and `asInstanceOf`
  (`.todo/m62`); on Preview 1 the `@WitImport` store has no host.
