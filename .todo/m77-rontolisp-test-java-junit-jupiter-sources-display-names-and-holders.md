# m77. `rontolisp test` of Java files: the sources, display names and holders past the conversions

Difficulty: Medium

Left after the implicit conversions, `@FieldSource` and `@EnumSource`'s range
(`.kb/java-frontend.md`, "An argument converted as JUnit converts it"), each refused by
name today:

- `@CsvFileSource` (`resources` read where the program is lowered, as a class literal's
  `getResourceAsStream` is; `files` where it runs; `numLinesToSkip`, `encoding`,
  `commentCharacter`, quoted values over lines), `@ArgumentsSource` (JUnit 6's
  `provideArguments(ParameterDeclarations, ExtensionContext)`), `classes` of `@ValueSource`,
  a composed annotation, `"Class#member"` of `@MethodSource`/`@FieldSource`.
- A display name of a parameterized run and with it a `TestInfo` of a parameterized test.
  Measured under JUnit 6.0.3: `[1] "a\"b", 'c', null, [1, 2], [x, null], 1.5` (strings and
  chars quoted and escaped, arrays by their elements); a `Named` its name, an argument set
  its name only; `name` is a `MessageFormat` pattern over arguments already made text
  (`{0,number,#.##}` fails to format, `{2}` past them stays as written, `''` a quote), an
  empty or blank name is `PreconditionViolationException: Configuration error:
  @ParameterizedTest on method [..] must be declared with a non-empty name.`, an unknown
  `{foo}` `JUnitException: The display name pattern defined for the parameterized test is
  invalid.`; a `TestInfo` after more columns than parameters is JUnit's competing
  `ParameterResolver`s. Without `-parameters` no argument is named.
- `cleanup` and `factory` of `@TempDir`; a constructor `@TempDir` under `PER_CLASS` or of a
  repeated test with a failure threshold (unprobed, refused).
- An annotation of an inherited test is written into the holder of the class that runs it,
  under THAT file's imports: an element naming what only the superclass's file imports is
  refused. A holder per declaring file would mend it.
