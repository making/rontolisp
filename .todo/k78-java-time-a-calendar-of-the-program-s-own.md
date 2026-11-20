# k78. java.time: a calendar of the program's own

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`, "java.time: what
k57 left".

k70 (2026-11-21) gave `AbstractChronology` (its `resolveDate` over a map, `equals`,
`hashCode`, `compareTo`, `toString`) and the members a JDK's `Chronology` has as defaults
(`date` and `dateYearDay` of an era, `dateNow`, `epochSecond`). What a calendar that is not
ISO's needs beyond that is refused or answered in ISO's terms:

- `Chronology.localDateTime`, `zonedDateTime` (both) and `period` are refused with an
  `UnsupportedOperationException`: a JDK answers its own date-time, zoned date-time and
  period of the calendar (`period(1, 2, 3)` of a calendar `Thin` is `Thin P1Y2M3D`; a
  `localDateTime` of a calendar whose dates are `LocalDate` is a JDK's internal
  `ClassCastException`).
- `ChronoLocalDate` declares abstract what a JDK gives defaults to (`toEpochDay`,
  `isLeapYear`, `lengthOfYear`, `compareTo`, `isAfter`, `isBefore`, `isEqual`, `until`,
  `with`, `plus`, `minus` returning the calendar's date), so a program's own date class
  writes more than under javac; `atTime` answers an ISO `LocalDateTime`.
- A formatter reads and writes in ISO's calendar whatever `withChronology` names
  (`Parsed` resolves the date fields itself, over its arrays: through
  `Chronology.resolveDate` over a map it was 56 KB more for every program reading a date).

To do: probe a JDK with two calendars of a probe's own (ISO's dates under another id, and
one whose years are shifted), then write the date-time, zoned date-time and period of a
calendar and the defaults, each paid for only where a program makes a `Chronology` that is
not ISO's (a question answered while lowering, as `Parsed.others()` is).
