# k43. Java front end: `java.time`, a formatter and the classes k35 left

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`,
"java.time". k35 (2026-11-20) wrote the classes a value is read from its text as, each
reading and writing its ISO-8601 form by a scanner of the library's own (`IsoText`).
Left, most valuable first; each is refused by name today.

- **`DateTimeFormatter`**: `ofPattern` (with a `Locale`: `.todo/k26` on what a locale
  is here), the `ISO_*` constants, `format` / `parse` of a formatter and the
  `format(DateTimeFormatter)` / `parse(CharSequence, DateTimeFormatter)` of each class,
  `withResolverStyle` and `ResolverStyle`, `DateTimeFormatterBuilder`. YAVI names it
  once (`constraint/CharSequenceConstraint`: `DateTimeFormatter.ofPattern(pattern)
  .withResolverStyle(ResolverStyle.STRICT).parse(x)`, behind `isoLocalDate` /
  `localDate(pattern)`), off the schema example's path. To settle by measuring: whether
  the `parse` of each class becomes a formatter's (one engine, but every program that
  reads a date then carries the engine) or stays `IsoText`'s, with the formatter a
  second reader of the same fields; the texts of months and days (`MMM`, `EEEE`,
  `getDisplayName`, `TextStyle`) need a locale's names -- English alone to begin with.
  The answers come from a grid as `Grid.java` of
  `.todo/artefacts/k35-java-time-the-classes-a-value-is-read-from-its-text-as/` is:
  patterns by letter and count, each resolver style, each refusal's index and message.
- **The smaller classes**: `MonthDay`, `OffsetTime` (`LocalTime.atOffset`,
  `LocalDate.atTime(OffsetTime)`), `InstantSource` (a `Clock` is one),
  `TemporalAdjusters` (`firstDayOfMonth`, `next(DayOfWeek)` ..), `TemporalQuery` /
  `TemporalQueries` and `query` (`from(TemporalAccessor)` is an `instanceof` over the
  library's classes today, so a program's own `TemporalAccessor` is not read),
  `IsoFields`, `WeekFields`, `JulianFields`, `ChronoPeriod`, `Chronology` /
  `IsoChronology` / `IsoEra` (`getChronology`, `getEra`; the other calendars stay out),
  `LocalDate.datesUntil`, `OffsetDateTime.timeLineOrder`, `Year.isValidMonthDay` /
  `atMonthDay`.
- **Not asked of a JDK yet**: what a `null` argument's `NullPointerException` says
  beyond the `parse` methods (the library names its own parameter where it checks, and
  raises the run time's elsewhere); `equals` and `hashCode` of two clocks (ours are the
  library's own, as `ValueRange`'s and `ZoneRules`').
- **Cost to watch** (k35's numbers, wasm): a program parsing one date is 141 KB, the
  nine classes reached through YAVI's table 120 KB, of which 14 KB are the top level's
  15 class objects -- a class object is made with the interfaces above it
  (`.kb/java-frontend.md`, "A class as a value"), and each date class has four to six.
  `Iso.yearStart` alone is 3.2 KB: three `floorDiv` of longs, each wrapped to 64 bits
  where no value can leave them.
