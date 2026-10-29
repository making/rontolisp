# Clojure: tools.reader's `#inst` reader on wasm (a portable `GregorianCalendar` chain)

Difficulty: Medium

tools.reader 1.3.6's 12-line probe prints the oracle's lines on all four backends but for
the `#inst` line, which stops both wasm backends (`.kb/clojure-frontend.md`, "Library
probes"; measured 2026-10-30). Its default `#inst` reader (`default_data_readers.clj`,
`construct-calendar`, then `construct-date`'s `.getTime`) runs, verbatim:

```clojure
(doto (GregorianCalendar. years (dec months) days hours minutes seconds)
  (.set Calendar/MILLISECOND (quot nanoseconds 1000000))
  (.setTimeZone (TimeZone/getTimeZone (format "GMT%s%02d:%02d" sign hh mm))))
```

Where no host is, `(GregorianCalendar. ...)` is `JAVA:NEW`, `Calendar/MILLISECOND` a
`JAVA:FIELD`, `TimeZone/getTimeZone` a `JAVA:STATIC`. The interpreter and the JVM run the
host classes. The front end's own `:C%CALENDAR` (`(ms offset zone)`, "Instants and UUIDs")
is immutable and never a construction's answer.

Plan: where no host is, make the chain the oracle's lenient calendar over fields:
the six-int construction (default zone UTC, like the oracle's measured JVM), `.set` of the
`Calendar` field constants, `TimeZone/getTimeZone` of a `GMT[+-]hh:mm` id (others refused by
name), `.setTimeZone`, and `.getTime`/`.getTimeInMillis` answering the own `:C%INST`. Mind
the JDK's laziness: fields set and not yet computed are read in the zone current when the
time is next computed (the probe sets the zone last, so the fields are the offset's local
time); once computed, `setTimeZone` keeps the instant. The calendar arithmetic already
exists (`%clojure-instant-*`, Julian before the 1582 cutover). Pin it in
`clojure-spec.yaml` (all four), then re-run the probe.

Done when the "Library probes" row's P1 and component columns read `=` for the 12-line
probe.
