# k52. Java front end: `java.time`, what the formatter left

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`, "java.time"
and "java.time: the formatter". k43 (2026-11-20) gave the subset `DateTimeFormatter`, its
builder and the queries, compared on a grid of 8,394 lines
(`.todo/artefacts/k43-java-time-a-formatter-and-the-classes-k35-left/`, whose README says
how to run it and holds the sizes). Left, most valuable first; each is refused by name
today.

- **The smaller classes** k35 left: `MonthDay`, `OffsetTime` (`LocalTime.atOffset`,
  `LocalDate.atTime(OffsetTime)`, `ISO_OFFSET_TIME` reading into one), `InstantSource`
  (a `Clock` is one), `TemporalAdjusters` (`firstDayOfMonth`, `next(DayOfWeek)` ..),
  `IsoFields`, `WeekFields`, `JulianFields`, `ChronoPeriod`, `Chronology` /
  `IsoChronology` / `IsoEra` (`getChronology`, `getEra`, `TemporalQueries.chronology()`,
  a formatter's `getChronology` / `withChronology`; the other calendars stay out),
  `LocalDate.datesUntil`, `OffsetDateTime.timeLineOrder`, `Year.isValidMonthDay` /
  `atMonthDay`.
- **The pattern letters that need them**: a locale's weeks (`Y`, `w`, `W`, and `e` and `c`
  of one or two letters: `WeekFields` of the five locales `Locale` has -- a JDK under
  `C.UTF-8` has the default locale `en`, whose weeks start on Monday, and `en_US` on
  Sunday, so the corpus cannot print them by the default locale), a quarter (`Q`, `q`:
  `IsoFields`), the modified Julian day (`g`: `JulianFields`), `ISO_WEEK_DATE`; then
  what needs data of CLDR: a period of the day (`B`: `midnight`, `in the morning`, its
  narrow `mi`) and a zone's name in words (`z`, `v`: `JST`, `Japan Standard Time`),
  `ofLocalizedDate` and its kind. Each is an `UnsupportedOperationException` by name
  when the pattern is read (`Patterns.refused`).
- **The formatter's other members**: `DecimalStyle`, `toFormat` (`java.text.Format`),
  `parseUnresolved` and `parse` at a `ParsePosition`, `withResolverFields`, a program's
  own `TemporalField` resolved by a formatter (`TemporalField.resolve`).
- **Where k43 does not follow a JDK** (`doc/*/java/deviations.md`): the era `CE` read
  leniently in English; a day of the week in an aligned week of the year read leniently
  (the probe found no one rule); the index errors a JDK throws inside its reading of a
  full localized offset. Not asked of a JDK yet: what a `null` argument's
  `NullPointerException` says beyond the `parse` methods; `equals` and `hashCode` of two
  clocks.
- **Cost to watch** (wasm): a date written by a pattern is 322,993 B, one read by a
  pattern 428,993, YAVI's `isoLocalDate()` constraint 946,257 against `Member`'s
  663,174. Leads, each to measure: a pattern that is a constant made into its parts
  while lowering (`Patterns` and the builder are 16 KB, and the parts no letter of the
  pattern needs go too; YAVI's `localDate(pattern)` passes its pattern on, so it keeps
  them all); the float arm of a generic `%string` (16 KB, which `"" + field` of an
  interface type brings: `.todo/k55`'s type flow); `Parsed` (38 KB, of which the
  conversion of time fields 12.7 KB) in a program whose patterns have no letter of a
  time.
