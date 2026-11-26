# m43. `rontolisp test`: a project's own AssertJ from its sources jar, as its choice

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first** ("A test API under its own names"):
AssertJ's sources are given to the front end, never read or copied.

What l86 measured and decided (2026-11-25; `.kb/java-frontend.md`, "Tests of a program"): AssertJ 3.27.7's
sources compile; the library's own part answers `org.assertj` in a run of tests, the sources
jar costing 14x the module and 5x the compile for ten checks. A project would choose its own
AssertJ for every check and every message AssertJ's own (the part lacks
`usingRecursiveComparison`, `asInstanceOf` ..: `.todo/m33`).

- What runs: l81's ten assertions print the JDK's bytes on the four legs; the message of a
  `Date` reaches `java.text.DateFormat` (the warnings' `reached through` lines), which throws
  `NoClassDefFoundError` until `.todo/k87`.
- What its checks say: of l81's 202 failing checks, 102 as under a JDK (the part: 187).
  63 throw `java.lang.AssertionError` where the JDK run, opentest4j on its class path, throws
  `org.opentest4j.AssertionFailedError` (the same text); 8 `NoSuchMethodError` of
  `Thread.getStackTrace()` in a group's check; 29 another text (a stack trace's frames). Ask
  AssertJ under a JDK, not its sources, what decides each.
- How a project says so. Not "AssertJ is on the source path": the Maven plugin's `test` goal
  puts every test dependency's sources jar there (`.kb/jvm-export.md`, "The plugin's `test`
  goal"), so every project using AssertJ would switch. An option of `rontolisp test` and a
  parameter of the plugin's goal; `JavaClasses.TEST_PACKAGES` then leaves `org.assertj.core.`
  to the source path. Measure what a failing check throws on the four legs against the
  JDK (opentest4j is the library's part).
- The size to bring down: of the IR of ten checks (2.31 MB), the class objects' generic types
  and members a representation reaches are 0.69 MB (`.todo/m18`).
- Pin: a `tests:` case of `java-spec.yaml` naming `dependencies:
  [org.assertj:assertj-core:3.27.7]` against the oracle.
