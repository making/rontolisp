# k57. Java front end: `java.time`, what k52 left

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`, "java.time:
the formatter" and "java.time: what the formatter left". k52 (2026-11-20) gave the
smaller classes, the ISO calendar, the fields of weeks, quarters and Julian days, their
pattern letters and the resolving of a field that is no `ChronoField`, compared on a grid
of 4,561 lines (`.todo/artefacts/k52-java-time-what-the-formatter-left/`, whose README
says how to run it and holds the sizes). Left, most valuable first; each is refused by
name today.

- **What needs data of CLDR**: a period of the day (`B`: `midnight`, `in the morning`, its
  narrow `mi`; how a JDK reads one back and resolves it with an hour), a zone's name in
  words (`z`, `v`: `JST`, `Japan Standard Time`; `ZoneId.getDisplayName`; read back among
  the zones a program holds, as `VV` is), `ofLocalizedDate` / `Time` / `DateTime` with
  `FormatStyle`, `localizedBy`. Each is an `UnsupportedOperationException` by name when
  the pattern is read (`Patterns.refused`) or a member refused by name. The words come
  from a running JDK for the five locales `Locale` has, under the Unicode entry of
  `THIRD-PARTY-NOTICES.txt`, as `Texts` holds them; the zone names only for the zones a
  program holds (k42's way).
- **The formatter's other members**: `DecimalStyle`, `toFormat` (`java.text.Format`),
  `parseUnresolved` and `parse` at a `ParsePosition` (`java.text.ParsePosition`),
  `withResolverFields` / `getResolverFields`; `IsoChronology.resolveDate` (the date
  fields `Parsed.resolveDate` resolves, over a map) and `AbstractChronology`.
- **Where k43 and k52 do not follow a JDK** (`doc/*/java/deviations.md`): the era `CE`
  read leniently in English; a day of the week in an aligned week of the year read
  leniently (the probe found no one rule); the index errors a JDK throws inside its
  reading of a full localized offset. Not asked of a JDK yet: whether a fraction of no
  fixed width appended right after a run ends it as a number does; what a `null`
  argument's `NullPointerException` says beyond the `parse` methods; `equals` and
  `hashCode` of two clocks.
- **Cost to watch** (wasm): a pattern computed where the program runs can name every
  letter, so it holds the parts of weeks, quarters and Julian days and the resolving of
  their fields: 429,380 -> 495,505 B (`WeekFields` 21.4 KB, `IsoFields` 12.6 KB, the top
  level 29.6 KB: measure what it holds); YAVI's `localDate(pattern)` passes its pattern
  on, so such a constraint pays it (k43's `yavi/Event.java` 946,257 -> 1,020,590 B: a
  constant the program's call passes, carried to the call of `ofPattern`, would name its
  letters alone). Leads, each to measure: the parts of the older
  letters made only where a pattern names them, now that the letters of each pattern are
  noted while lowering (`JavaLowering.namesPattern`: an offset, a zone's name, an
  instant, a fraction are in every pattern program); a constant pattern made into its
  parts while lowering (`Patterns` and the builder are 16 KB); the masked `int` / `long`
  operations of `WeekFields`' arithmetic (`.todo/k56`); the float arm of a generic
  `%string` (16 KB, `"" + field` of an interface type: `.todo/k60`'s type flow); `Parsed`'s
  conversion of time fields in a program whose patterns have no letter of a time.
