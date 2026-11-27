# n09. `rontolisp test` of Java files: the BDD soft assertions and what the extension leaves

Difficulty: Low

What m83 left of AssertJ's soft assertions, each refused by name today
(`.kb/java-frontend.md`, "Tests of a program", "Soft assertions"):

- `BDDSoftAssertions`, `AutoCloseableBDDSoftAssertions` and their providers
  (`Java6BDDSoftAssertionsProvider`, `BDDSoftAssertionsProvider`): the `then..` twins of
  `Java6StandardSoftAssertionsProvider` / `StandardSoftAssertionsProvider`, one line each
  (`Soft.soft(new XAssert(actual), this)`). Measured under the extension: a
  `BDDSoftAssertions` parameter is given as a `SoftAssertions` one is.
- `SoftAssertionsProvider.assertSoftly(Class, Consumer)`: an instance by the class's
  constructor of no parameter (reflection, as `AbstractSoftAssertions.proxy`).
- `@ExtendWith(SoftAssertionsExtension.class)` on a test METHOD. Measured (JUnit 6.0.3,
  AssertJ 3.27.7): it applies to that test, and the `@InjectSoftAssertions` fields of its
  class are given too. `JavaTests.check` refuses it (`.. on what is no class`); the
  flag is `Suite.soft`, per class.
- `SoftAssertionsExtension.getSoftAssertionsProvider` / `getAssertionErrorCollector`
  (an `ExtensionContext`, which the run has none of): refuse stays, unless an extension
  of the program's is ever run.

Pin each in the corpus case `soft-assertions-collect-what-fails-and-the-chain-goes-on`
(`java-spec.yaml`), against the oracle.
