# k93. java.time: an instant read beside a date or a time

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`, "java.time: the
formatter" and "java.time: a calendar of the program's own".

Found by k78's probes (2026-11-21, JDK 25.0.5), in ISO's calendar too:

- A text giving an instant (`appendInstant`) and a zone (`withZone`) beside a date or a time of
  day: a JDK checks them against the instant's and refuses another (`Conflict found: Fields
  resolved to two different dates: 2026-03-07 2026-03-08`; `Conflict found: HourOfDay 11 differs
  from HourOfDay 10 while resolving  SecondOfDay`, the instant's time made a second of the day
  before the time fields are resolved). `Parsed.instant()` answers the instant's date and time and
  leaves the fields read. Probe it again under a JDK.
- In a program with no calendar of its own, a formatter of ISO's calendar refusing a zone of a
  `YearMonth`, a `Year` or a `MonthDay` shows `Unable to extract ZoneId from temporal 2026-03 with
  chronology ISO`; a JDK adds the calendar only where it is not the value's own (`2026-03`).
  `Writing.shown()` tells a date by its epoch day; a program with a calendar of its own asks the
  value (`DateTimeFormatter.calendared`). Asking it in every program makes the question of a
  calendar (`TemporalQueries.calendars()`) true in any program writing a zone: measure first.

To do: probe the order a JDK resolves an instant in against the date, the time, the offset and the
excess days, then resolve it there, in ISO's calendar and in a calendar of the program's own.
