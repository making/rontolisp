# k79. java.time: what a formatter costs, the leads k70 measured

Difficulty: Medium

**Read `.kb/java-frontend.md`, "java.time: what k57 left", "What a program pays" first.**

The sizes on 2026-11-21 (wasm, after k70; the base `358f6b493` in brackets): a date written
by a pattern 304,922 (306,048), read 402,498 (403,287); a pattern computed where the program
runs, written and read 512,488 (508,946); k43's `yavi/Event.java` 994,489 (989,578), whose
`localDate(pattern)` passes its pattern on and so names every letter. Leads, each to measure:

- **A constant passed on to `ofPattern`**: a parameter every call of whose method passes a
  constant names those constants' letters alone (YAVI's `localDate(pattern)`); today it is
  a pattern computed where the program runs.
- **Resolving leniently**: only the lenient day of a week in an aligned week is behind
  `Parsed.lenients()` (the reach of `withResolverStyle`, the one way to a lenient
  formatter; 1.6 KB). Every other lenient branch of `Parsed` (the dates, the time fields,
  the excess days) is in every program that reads a date.
- **The masked `int` / `long` operations of the parts** (`.todo/k56`'s measurements:
  `DayPeriod.parse`, `ZoneNames.read()` and `Parsed.hourInPeriod` are 4 to 6 KB each).
- **The names of every region**: a program holding them all and writing a zone's name in
  words carries 90,405 B of names (26,798 read); the English rows repeat across a
  metazone's zones (250 distinct among 604), a dictionary of rows the lead.
