# m83. `rontolisp test` of Java files: AssertJ's soft assertions

Difficulty: High

AssertJ's soft assertions (`SoftAssertions`, `assertSoftly`, `AutoCloseableSoftAssertions`,
`SoftAssertionsExtension`) are refused by name: the library's part has no such class
(`.kb/java-frontend.md`, "Tests of a program", "AssertJ's checks by reflection").

What makes them hard: a soft check that fails records its error and the chain goes on
(`softly.assertThat(x).isEqualTo(1).startsWith("a")` runs both), and a check calling
another (`isInstanceOf` calls `isNotNull`) ends at its first failure. AssertJ gets this from
Byte Buddy subclasses of its assertion classes that catch at the outermost call. The
library's checks `throw failure(..)` where they fail, so neither a flag on the assertion nor
a catch in the test's code gives that: each public check would need a wrapper, written by
hand in every assertion class (a try in every check of every run of tests, measured cost
unknown) or made by the front end (a subclass whose public methods catch at the outermost
call, as AssertJ's proxies do).

- Decide between the two by measuring: the wrapper's cost on `UrlCodecTest` and `SqlTest`
  (wasm, 240,369 / 294,333 B after m62), and what the front end would need to make such a
  subclass.
- Each message asked of AssertJ 3.27 under a JDK (`assertAll`'s `MultipleFailuresError`
  text, `errorsCollected`, `wasSuccess`), pinned by the corpus case's `q/AssertjTest.java`.
