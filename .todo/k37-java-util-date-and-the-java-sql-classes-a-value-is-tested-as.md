# k37. Java front end: `java.util.Date` and the `java.sql` classes a value is tested as

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first.**

Met by YAVI's schema example (`Yavi.schema()` .. `fromMap`) behind k30, measured
2026-11-20: `the class java.util.Date`, in `core/UntypedInput.convert`
(`value instanceof java.util.Date date`), then `java.sql.Date`, `Time` and
`Timestamp` in its `SqlTypes` (`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`, `schema/`,
edit 3 of `patch.sh`, the last one since k35). On the milestone's path (`.todo/k33`);
what the three convert to is there since k35 (`.kb/java-frontend.md`, "java.time").

- No program on wasm makes such a value: the classes are there to be TESTED
  (`instanceof`, `getClass().getSuperclass()` to `null`, a name starting
  `java.sql.`) and to convert (`toLocalDate()`, `toLocalTime()`,
  `toLocalDateTime()`, `toInstant()`). A class nobody has is a name until reached
  (k24), and an `instanceof` reaches it: keep that refusal, it is how a missing class
  is found.
- `java.util.Date`: milliseconds since the epoch, `getTime`, `equals`, `hashCode`,
  `compareTo`, `before` / `after`, `toInstant`, `Date.from`; its `toString` is in the
  default zone: k35 made `ZoneId.systemDefault()` the host's offset now, a
  `ZoneOffset` (no region has a name here, `.todo/k42`), which is what the conversions
  of `java.sql` read too. The deprecated fields (`getYear`) stay refused by name.
- `java.sql` is a module of its own on a JDK: `library/java/sql/` is the first package
  of the library outside `java.base`; say so where the library's packages are listed
  (`doc/*/java/index.md`).
