# k42. Java front end: `java.time`, the rules of a region

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`,
"java.time": k35 (2026-11-20) gave the library the zones that keep one offset and left
this out, with the numbers below.

Today a region with rules of its own is unknown: `ZoneId.of("Europe/Paris")` throws
`ZoneRulesException: Unknown time-zone ID`, `ZonedDateTime.parse` leaves a bracket naming
one unread (a refusal, where a JDK reads it), and `ZoneId.systemDefault()` is the host's
offset now as a `ZoneOffset`, so a date of another season is read at today's offset.
`doc/*/java/deviations.md` says so.

- **What the rules cost**
  (`.todo/artefacts/k35-java-time-the-classes-a-value-is-read-from-its-text-as/Zones.java`,
  asked of JDK 25's `java.time.zone`): 604 names, 322 sets of rules, 18,001 changes of
  offset, 222 yearly rules -- some 123 KB in a plain encoding, against 1,215 KB for
  YAVI's schema example and 179 KB for a program parsing one zoned text. `ZonedDateTime.parse`
  reads any name, so it reaches all of them.
- **To decide**: the table generated from a running JDK's public API and pinned to it,
  as `UnicodeNormalizationTablesTest` pins the normalization tables (the tz database is
  public domain; an entry of `THIRD-PARTY-NOTICES.txt` with its `ThirdPartyNoticesTest`
  rows); a tighter encoding (the changes of most regions since 1970 follow few yearly
  rules; the history before could be a second table reached only by a date before it);
  and who pays -- a name written as a constant (`ZoneId.of("Asia/Tokyo")`) could bring
  its own rules alone, as a charset named by a constant brings no `Utf16`, while a
  computed name or a zoned text brings all. An option that leaves the regions out
  would keep today's sizes for a program that wants them.
- **What it changes in the library**: `ZoneId.fixed()` is the one question every class
  asks of a zone; with rules it becomes the offset at an instant and the offsets of a
  local date-time (none in a gap, two in an overlap). `ZonedDateTime` then settles gaps
  and overlaps (`ofLocal`'s preferred offset, `withEarlierOffsetAtOverlap`, the
  date-based units moving the local time and the time-based ones the instant: today
  both are the same), `ZoneRules` gets its transitions (`ZoneOffsetTransition`,
  `getTransition`, `nextTransition`), `ZoneId.getAvailableZoneIds`.
- **The host's zone**: `ZoneId.systemDefault()` could then be the region `TZ` names
  where it names one the table has (`%host-getenv`; `%local-time-offset` answers an
  offset, not a name, and wasm reads `TZ` itself: `.kb/time-environment-builtins.md`).
- The grid of k35 has no region on purpose; a second grid (a seeded walk of instants
  and local date-times around every region's changes) is how the rules are checked.
