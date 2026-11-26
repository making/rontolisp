# m62. `rontolisp test` of Java files: what m33 left of AssertJ

Difficulty: Medium

The library's part of AssertJ (`.kb/java-frontend.md`, "Tests of a program") lacks, each
refused by name today:

- The checks by reflection: `usingRecursiveComparison`, `hasFieldOrPropertyWithValue`,
  `extracting` by name (reflection over the program's and the library's classes is there,
  `.kb/java-frontend.md`, "Reflection in full"; mind what a table costs where asked).
- Soft assertions.
- `assertThat` of a `Path`, a `File`, a `Future`, a `Class`, the dates, a `Comparable` of the
  program's.
- `within` and `isCloseTo`, `asInstanceOf` (`InstanceOfAssertFactories`), `tuple`,
  `filteredOn` by a name, a map's `containsExactly`.

Each failure message is asked of AssertJ 3.27 running under a JDK (the corpus case's JDK
leg is the oracle); `KeyValueSessionStoreTest` uses the recursive comparison and
`asInstanceOf` (`.todo/m61`).
