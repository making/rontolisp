# n17. `rontolisp test` of Java files: composed annotations, `@ArgumentsSource`, a program's `TempDirFactory`

Difficulty: Medium

Left after the display names, the CSV files and the cleanup modes
(`.kb/java-frontend.md`, "Tests of a program"), each refused by name today:

- A composed annotation: an annotation type of the program carrying JUnit's annotations
  (`@Test`, `@ParameterizedTest`, a source, `@Tag`, `@Timeout`, ...), which JUnit finds
  meta-present on what it annotates. Probe first which JUnit reads that way, the order of a
  direct and a meta-present one, and a repeatable source written both ways. The elements
  of a meta-annotation are expressions of the annotation type's file: `JavaTests.written`
  takes the class they are written in, so `Annotated` has to carry each annotation's
  class instead of the method's owner.
- `@ArgumentsSource`: JUnit 6 calls `provideArguments(ParameterDeclarations,
  ExtensionContext)`, 5.x `provideArguments(ExtensionContext)`.
- `@TempDir(factory = ...)` of the program's: `createTempDirectory(AnnotatedElementContext,
  ExtensionContext)`, then `close()`.

The last two need an `ExtensionContext` the run makes. Its abstract methods differ by
JUnit version, so it can be a class generated from the interface the source path holds,
each method a refusal where the run has no answer (`getStore`, `publishReportEntry`)
and the rest answered (`getDisplayName`, `getTestClass`, `getTestMethod`, `getElement`,
`getUniqueId`?). Probe what a provider and a factory see of it under JUnit 6.0.3.
