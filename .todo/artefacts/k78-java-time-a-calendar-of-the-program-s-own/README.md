# k78: a calendar of the program's own, measured 2026-11-21

What was learned is in `.kb/java-frontend.md`, "java.time: a calendar of the program's own". Here:
the probes the library was compared on, and the sizes.

## The probes

Thirteen programs asked of a JDK 25 (25.0.5), each with calendars of the probe's own: `Thin`,
ISO's dates (`LocalDate`) under another id and type; `Shifted`, `Alpha`, `Beta`, `Gamma`, whose
years are ISO's shifted and whose dates are a class of the probe's own (`Beta`'s months of a year
are no fixed count, `Gamma`'s thirteen); `Bare`, a date leaving every default to the interface.

| Probe | What it asks |
|---|---|
| `P1` | `localDateTime`, both `zonedDateTime`, `period` and `atTime` of the two calendars; what they refuse and wrap |
| `P2` | the calendar's date-time: every field, every unit added and counted, `with`, adjusters, amounts, `atZone`, the order against ISO's, the queries, `from`, a formatter |
| `P3` | the calendar's zoned date-time: the same, a gap and an overlap in Paris, the offset and the instant set, the zones |
| `P4` | the calendar's period: every member, its hash, `addTo` and `subtractFrom` of dates of each calendar |
| `P5` | the defaults of `ChronoLocalDate` over the probe's date and over `Bare` |
| `P6` | a formatter of each calendar writing twelve kinds of value and reading eighteen kinds of text |
| `P7`, `P8` | the week, quarter and Julian fields of a date of the probe's own; the last three lines of `P7` name `appendChronologyId` and `appendChronologyText`, which the subset refuses, so it runs on the four backends without them |
| `P9` | the checks of what a default made, the order of two calendars' values, the words of an era by a calendar's type, `from`, the nulls, a zone the value lacks |
| `P10` | months of no fixed count, cross-calendar checks, the era words of nine calendar types |
| `P11` | the excess days of a time read, a reading of an instant, the nulls of the defaults, times past the ends |
| `P12`, `P13` | each time unit added past the end; an instant read beside an offset, a date and a time |

```bash
R="java -cp target/classes am.ik.rontolisp.cli.RontoLispCli"   # or -jar the exec jar
java P2.java > jdk.txt
$R P2.java > interpreter.txt
$R P2.java -o P2.jar && java -jar P2.jar > jvm.txt
$R P2.java -o p2.wasm && wasmtime run p2.wasm > wasm.txt
$R P2.java -o p2-c.wasm --component && wasmtime run p2-c.wasm > comp.txt
```

The four backends print the same lines for every probe (wasm 284 to 646 KB). Where they differ
from the JDK's: the probe's own null refused with no message where a JDK's helpful message names a
parameter (`P1`, `P9`, 27 lines of `P11`), a region's class name (`P3`), the hash codes made of a
class's identity (`P4`), a map printed in an order of a JDK's run (`P6`, `P9`, `P13`), the
conflict a JDK finds first among the fields left (`P6`), the week-based year set and read in a
calendar not ISO's (`P7`, `P8`), the era words of five calendar types the library has no words for
(`P10`), and an instant read beside a date or a time (`P13`). The last three are `.todo/k92` and
`.todo/k93`. `P12` was where ISO's `LocalDateTime` added half days past the end otherwise than a
JDK; it prints a JDK's lines now.

## Sizes (wasm)

Before (`0c33a3046`) and after, one statement in `main`:

| Program | Before | After |
|---|---|---|
| `LocalDate.parse("2020-01-05")` | 137,019 | the same bytes |
| `ZonedDateTime.of(..).plusHours(1)` in Tokyo | 154,589 | the same bytes |
| `LocalDate.of(..).plusDays(1)` | 97,219 | the same bytes |
| a date written by `"uuuu-MM-dd"` | 281,634 | the same bytes |
| a date read by `"uuuu-MM-dd"` | 358,892 | 358,975 |
| a pattern computed where the program runs, written and read | 449,982 | 450,120 |
| `ISO_LOCAL_DATE_TIME.format` | 251,361 | the same bytes |
| a date by `ofLocalizedDate(FormatStyle.MEDIUM)`, written and read | 384,066 | 382,845 |
| `ZonedDateTime.parse(.., ISO_ZONED_DATE_TIME)` of a text in Tokyo | 373,288 | 373,377 |
| a `LocalDate` through `ChronoLocalDate` (calendar, `atTime`, order) | 100,795 | the same bytes |
| `Period` added and summed | 170,384 | the same bytes |
| YAVI `Member.java`, `Booking.java` | 638,282, 1,101,024 | 638,295, 1,101,041 |
| k43's `yavi/Event.java` (a pattern passed on) | 921,258 | 921,352 |

A program reading a text pays the two fields `Parsed` holds for a calendar of the program's own
(their names and slots, 83 to 138 B); the medium form's 1.2 KB less is the dispatch's layout.

A program making a calendar of its own (`Thin`):

| Program | Before | After |
|---|---|---|
| a date written in it | 300,124 (in ISO's calendar) | 293,985 |
| a date read in it | 378,171 (in ISO's calendar) | 435,109 (its `resolveDate` over a map) |
| its period and its date-time printed | refused | 181,020 (ISO's of `IsoChronology`: 105,646) |

Three measurements that changed the code. Writing in the calendar first held a static list of
the fields of a date, which every program with a formatter initialized (a date read by a pattern
360,677 B): the list is made where the refusal needs it. The loop over `ChronoField.values()` it
replaced made every constant of `ChronoField` (3 KB). The zoned date-time of the calendar, which
a formatter of a zone needs, was in every program writing in a calendar of its own (the date
written, 324,407 B): it is behind whether the program reaches `withZone`, so a program that
cannot give a formatter a zone writes with none of it (reading an instant still makes one).
