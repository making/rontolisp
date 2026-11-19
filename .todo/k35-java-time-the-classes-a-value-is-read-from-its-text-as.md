# k35. Java front end: `java.time`, the classes a value is read from its text as

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first.** The library is Java source embedded in
every compiled program that reaches it; written from the API specification and from a
JDK's answers to probes, never from its source.

What stops the first example of YAVI's README (`Yavi.schema()` .. `fromMap`) since k30,
measured 2026-11-20: `the class java.time.LocalDate`, at `core/UntypedInput`'s table of
the types read from their string form (`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`, `schema/`,
edit 1 of `patch.sh`). On the milestone's path (`.todo/k33`). Taken out of
`.todo/k26`, whose list has "`java.time` beyond `Duration`".

- What that table names, each by `X::parse`, so each is lowered whole where the program
  reaches the table: `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`,
  `ZonedDateTime`, `Instant`, `Year`, `YearMonth`, `Period` (`Duration` is there
  since k05); `Year.of(int)` and `DateTimeException` in the conversion of a number.
  `.todo/k37` adds `toLocalDate()` .. `toInstant()` of the `java.sql` classes.
- What YAVI names beyond the schema example (by import, 380 files): the eight temporal
  constraint classes (`constraint/LocalDateConstraint` ..: `isAfter` / `isBefore`, the
  fields a range reads, `now(Clock)`, `Clock.systemDefaultZone()`), 9 files with
  `Clock`, one each with `DateTimeFormatter.ofPattern`, `ResolverStyle`,
  `Temporal` / `TemporalAccessor` / `TemporalAmount` / `TemporalField` and the three
  `chrono` interfaces. A probe listing the members met (`refused.py` of the k12
  artefacts) says which of these a program validating a date reaches.
- To settle by measuring: the ISO forms `parse` and `toString` share (a seeded grid
  under a JDK: years outside 0000-9999, fractions, offsets with seconds); the
  proleptic calendar by arithmetic over the epoch day; **`ZonedDateTime` needs the
  rules of a region** (`Europe/Paris`): the tz database is megabytes, so decide between
  fixed offsets and `Z` only (a region refused by name where it is read), a table
  generated from a running JDK and pinned to it as `.kb/unicode-normalization.md`'s
  tables are, and the host's rules through an import; `now()` over the clock the run
  time has (`System.currentTimeMillis`), whose zone on wasm is not known.
- Cost to watch: a program reaching the table pays every class in it. The schema example
  is 1,009,805 B of wasm with the table cut to `Duration` and `URI`.
