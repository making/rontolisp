# k37. Java front end: `java.util.Date` and the `java.sql` classes a value is tested as

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first.**

Met by YAVI's schema example (`Yavi.schema()` .. `fromMap`) behind k30, measured
2026-11-20: `the class java.util.Date`, in `core/UntypedInput.convert`
(`value instanceof java.util.Date date`), then `java.sql.Date`, `Time` and
`Timestamp` in its `SqlTypes` (`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`, `schema/`,
edit 3 of `patch.sh`). On the milestone's path (`.todo/k33`); needs `.todo/k35` for
what the three convert to.

- No program on wasm makes such a value: the classes are there to be TESTED
  (`instanceof`, `getClass().getSuperclass()` to `null`, a name starting
  `java.sql.`) and to convert (`toLocalDate()`, `toLocalTime()`,
  `toLocalDateTime()`, `toInstant()`). A class nobody has is a name until reached
  (k24), and an `instanceof` reaches it: keep that refusal, it is how a missing class
  is found.
- `java.util.Date`: milliseconds since the epoch, `getTime`, `equals`, `hashCode`,
  `compareTo`, `before` / `after`, `toInstant`, `Date.from`; its `toString` is in the
  default zone, which wasm does not know (`.todo/k35`'s question). The deprecated
  fields (`getYear`) stay refused by name.
- `java.sql` is a module of its own on a JDK: `library/java/sql/` is the first package
  of the library outside `java.base`; say so where the library's packages are listed
  (`doc/*/java/index.md`).
