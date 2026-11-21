# k92. java.time: the weeks, the Julian days and the id of a calendar of the program's own

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`, "java.time: a
calendar of the program's own".

k78 (2026-11-21) wrote a calendar's date-time, zoned date-time and period, the defaults of its
dates, and a formatter writing and reading in it. What a calendar that is not ISO's still gets in
ISO's terms or not at all:

- `WeekFields`' fields compute a week-based year from ISO's years: `with` of one on a date of a
  calendar whose years are ISO's a hundred later answers a date a hundred years off (a JDK: the
  calendar's year), and reading `YYYY-ww-e` in such a calendar makes ISO's date, which the
  calendar's formatter then refuses (`ChronoLocalDate must use the effective parsed chronology`).
  `JulianFields`' resolving makes ISO's date the same way. Probes: `P7`'s `with wby` and `P8`'s
  `value Y parse` in `.todo/artefacts/k78-java-time-a-calendar-of-the-program-s-own/probes/`.
- `DateTimeFormatterBuilder.appendChronologyId()` and `appendChronologyText(TextStyle)` are
  refused by name (`P7`'s last lines: a JDK writes the calendar's id, `Shifted`, `ISO`).
- A calendar whose type is one of the five other types a JDK has CLDR's words for (`buddhist`,
  `japanese`, `roc`, `islamic-umalqura` and its kin) writes its eras as numbers here, where a JDK
  writes `BE`, `Meiji`, `Minguo`, `AH` (and the Islamic months); `P10`.

To do: probe a JDK with a calendar whose years do not fall on ISO's (a year starting in March, a
leap rule of its own) for every `WeekFields`' field read, set and resolved, then compute the weeks
over the calendar's own dates (its first day of a year, its year's length), paid for only where a
program makes a calendar of its own (`IsoFields.ownCalendars()` shows the question).
