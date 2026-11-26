# m33. `rontolisp test` of Java files: what l87 left

Difficulty: Medium

l87 (2026-11-25) added a directory as the target, `@TempDir`, the assumptions and `abort`
(skips as surefire counts them) and the closing of `AutoCloseable` arguments
(`.kb/java-frontend.md`, "Tests of a program"). Left, each refused by name today:

- The suites of the tree's modules that do not run yet: the servlet container's (a Tomcat
  and a Jetty beside it, `@EnumSource` over containers, `DiscoveryTest` compiling with
  `javax.tools`), the driver's against a server (Testcontainers), the key-value store's
  (`usingRecursiveComparison`, `asInstanceOf`). 4 of 10 test classes run
  (`JavaSpecE2eTest#theSuitesOfTheTreesModulesRunUnchanged`).
- JUnit Jupiter (`JavaTests.RAN` is what a run runs): `@RepeatedTest`, `@TestFactory`,
  `@TestInstance`, `@Timeout`, `@Tag`, `@Order` and `@TestMethodOrder`, `TestInfo` and the
  other parameters JUnit resolves, a constructor with parameters (a `@TempDir` one), a test
  or lifecycle method an interface declares (refused since l87: the runner walks
  superclasses only), `@CsvFileSource`, `@ArgumentsSource`, `@FieldSource`, the elements of
  the sources not read, a composed annotation, the implicit conversions past the primitive
  types, their boxes and enums (`Path`, `URI`, `UUID`, `BigDecimal`, the dates), a display
  name of a run's arguments, `cleanup` and `factory` of `@TempDir`.
- AssertJ: the checks by reflection (`usingRecursiveComparison`,
  `hasFieldOrPropertyWithValue`, `extracting` by name; reflection over the program's and
  the library's classes is there, `.kb/java-frontend.md`, "Reflection in full"), soft
  assertions, `assertThat` of a `Path`, a `File`, a `Future`, a `Class`, the dates, a
  `Comparable` of the program's, `within` and `isCloseTo`, `asInstanceOf`, `tuple`,
  `filteredOn` by a name, a map's `containsExactly`.
- An annotation of an inherited test is written into the holder of the class that runs it,
  under THAT file's imports: an element naming what only the superclass's file imports is
  refused. A holder per declaring file would mend it.
- A failure's line has no source position; the interpreter and the JVM could give the
  failing assertion's file and line as an uncaught exception's location line does.
