# k36. Java front end: `java.util.UUID`

Difficulty: Low

**Read `.kb/java-frontend-provenance.md` first.**

Met by YAVI's schema example (`Yavi.schema()` .. `fromMap`) behind k30, measured
2026-11-20: `the class java.util.UUID` in `core/UntypedInput`'s table of the types
read from their string form, `UUID.fromString` after a pattern of the canonical form
(`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`, `schema/`, edit 2 of `patch.sh`). On the milestone's path (`.todo/k33`).

- Two longs: `fromString` (a JDK takes forms that are not canonical, `1-1-1-1-1`:
  learn the rule from its answers), `toString`, `equals`, `hashCode`, `compareTo`
  (signed, as specified), `getMostSignificantBits` / `getLeastSignificantBits`,
  `version`, `variant`.
- `randomUUID()` over the random source the run time has for `Random()`;
  `nameUUIDFromBytes` needs MD5, which the library does not have: refused by name
  until a program asks.
- YAVI names it in two files; `jsonschema/JsonSchema` compares `UUID.class`.
