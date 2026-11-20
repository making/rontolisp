# k70. Java front end: `java.time`, what k57 left

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`, "java.time:
the formatter", "java.time: what the formatter left" and "java.time: the names in words,
the localized forms and a day's periods". k57 (2026-11-20) gave the localized forms, a
zone's name in words (`z`, `v`, `appendZoneText`, `getDisplayName`), a day's period (`B`)
and `localizedBy`, compared on a grid of 2,350 lines
(`.todo/artefacts/k57-java-time-what-k52-left/`, whose README says how to run it and holds
the sizes). Left, most valuable first; each is refused by name today.

- **The formatter's other members**: `DecimalStyle` (`withDecimalStyle`, `getDecimalStyle`:
  a JDK's `DecimalStyle[0+-.]`), `toFormat` with or without a query (`java.text.Format`,
  `FieldPosition`, `ParseException`), `parseUnresolved` and `parse` at a `ParsePosition`
  (`java.text.ParsePosition`), `withResolverFields` / `getResolverFields`;
  `IsoChronology.resolveDate` (the date fields `Parsed` resolves, over a map) and
  `AbstractChronology`; `ofLocalizedPattern`, `appendLocalized(String)` and
  `getLocalizedDateTimePattern(String, ..)` (CLDR's skeletons: data a running JDK answers
  for the five locales, as `Texts` holds the localized patterns).
- **Where k43 and k52 do not follow a JDK** (`doc/*/java/deviations.md`): the era `CE`
  read leniently in English; a day of the week in an aligned week of the year read
  leniently (the probe found no one rule); the index errors a JDK throws inside its
  reading of a full localized offset. Not asked of a JDK yet: whether a fraction of no
  fixed width appended right after a run ends it as a number does; what a `null`
  argument's `NullPointerException` says beyond the `parse` methods (`localizedBy`'s is a
  JDK's helpful message, matched); `equals` and `hashCode` of two clocks.
- **Cost to watch** (wasm): a pattern computed where the program runs can name every
  letter: 429,380 B before k52, 495,472 before k57, 532,923 after (k57 added a zone's name
  in words, 20.6 KB of functions, and a day's period, 12.2 KB); YAVI's `localDate(pattern)`
  passes its pattern on, so k43's `yavi/Event.java` is 1,057,686 B (946,257 before k52).
  Leads, each to measure: a constant the program's call passes, carried to the call of
  `ofPattern`, would name its letters alone; the masked `int` / `long` operations of the
  parts' arithmetic (`.todo/k56`: `DayPeriod.parse`, `ZoneNames.read()` and
  `Parsed.hourInPeriod` are 4 to 6 KB each for a little arithmetic). A program holding
  every region and writing a zone's name carries every region's names, 90,405 B written
  and 26,798 B read (k43's grid 1,197,986 -> 1,371,635): the English rows repeat across a
  metazone's zones (250 distinct among the 604 zones), a dictionary of rows the lead.
- **Names fixed when the table is made**: a name a JDK writes as an offset
  (`GMT-05:00`) is the zone's offset at the moment it runs, and `zone-names.txt` holds it
  as of the JDK that generated the file; a change of a standard offset scheduled after
  that (none pending in tz database 2026e) would make the two differ until the file is
  generated again. The same for the spans where a JDK writes the other time's name.
