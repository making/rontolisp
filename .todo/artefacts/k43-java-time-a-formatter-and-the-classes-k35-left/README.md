# k43: `java.time.format`, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "java.time: the formatter". Here: the grid
the library was compared on, a YAVI program over its date constraint, and the sizes.

## `Grid.java`

8,394 lines under a JDK 25 (25.0.5): every pattern letter at each count, written and
shown (`toString`), and the refusals of a pattern; writing by each letter over seven
date-times and twelve zoned values; numbers by width and sign style, runs of adjacent
numbers, reduced years and fractions read strictly and leniently; the words of months,
days, eras and half days in the five locales and every style, case-insensitively and
leniently; the 22 offset patterns with each kind of no-offset text; the localized offset;
zone names; every resolver style over the date fields, the time fields alone and in every
combination with an instant, the conversions between them and their conflicts; the ISO
forms written over 27 values and read; the queries and `from` of every class over a
parse; the builder's members, refusals, pads, defaults and optional parts; and the 503
texts of k35's grid read through the ISO forms. The four backends print the JDK's lines,
all of them.

```bash
R="java -cp target/classes am.ik.rontolisp.cli.RontoLispCli"   # or -jar the exec jar
java Grid.java > jdk.txt                                       # 2.0 s
$R Grid.java > interpreter.txt                                 # 7.2 s
$R Grid.java -o Grid.jar && java -jar Grid.jar > jvm.txt       # 2.2 s, 298,178 B
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt                 # 0.8 s, 1,072,395 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt # 0.9 s, 1,076,764 B
```

The program names `Asia/Tokyo` and `Europe/Paris`, so it holds those two regions and reads
their names in a text. Not asked, since no line can be the same: what a JDK prints by an
object's identity (`parseDefaulting` in a formatter's text) or a lambda's name (the
refusal of `appendZoneRegionId` for a value with an offset alone), the index errors a JDK
throws inside its reading of a full localized offset (`OOOO` reading `GMT+1`) and of
`appendInstant(0)`, the era `CE` a JDK reads leniently in English, and a lenient day of
the week in an aligned week of the year (the probe found no one rule: Friday in a week
starting on a Friday is six days on, Monday in one starting on a Monday one day back).
Where several fields of a time with no hour are out of range, a JDK refuses them in an
order that changes from run to run; the grid has one such field a line.

## `yavi/Event.java`

YAVI's `isoLocalDate()` (`CharSequenceConstraint`: `DateTimeFormatter.ofPattern(pattern)
.withResolverStyle(ResolverStyle.STRICT).parse(x)`), written here over its public API.
Run as `--java-source-dep am.ik.yavi:yavi:0.18.0` (the JDK with the yavi jar on its class
path): the JDK's four lines on the four backends; wasm 946,257 B, the component 950,511 B,
the jar 341,465 B.

## Sizes (wasm)

The programs of k35's and k42's tables, before (`f68ec7f60`) and after:

| Program | Before | After |
|---|---|---|
| YAVI `Member.java` | 663,174 | the same bytes |
| YAVI `Booking.java` | 1,204,017 | 1,204,018, the same forms in another order |
| k35 `shelf/Loan.java` | 1,179,292 | 1,179,283, the same forms in another order |
| k35 `Grid.java` | 1,063,142 | the same bytes |
| k42 `Regions.java` | 594,888 | 594,916, the same forms in another order |
| `LocalDate.parse`, `LocalTime.parse`, `LocalDateTime.parse` | 141,206, 136,951, 149,096 | the same bytes |
| `OffsetDateTime.parse`, `ZonedDateTime.parse`, `Instant.parse` | 156,824, 178,799, 154,872 | the same bytes |
| `Year.parse`, `YearMonth.parse`, `Period.parse`, `Duration.parse` | 116,272, 130,538, 146,168, 155,035 | the same bytes |
| `LocalDate.of(..).plusDays(1)`, `LocalDate.now().getYear()` | 106,229, 118,582 | the same bytes |
| a date `plus(1, ChronoUnit.MONTHS)`, `until(.., DAYS)` | 174,653, 167,935 | 174,657, 167,943, the same forms in another order |
| `ZonedDateTime.of(..).plusHours(1)` in UTC, in Asia/Tokyo | 139,792, 190,042 | the same bytes |

"The same forms in another order": the lines of `--dump-ir` that differ are equal as
multisets of tokens (the arms of `%string` and `%class-name`, whose order follows the
class table: `LocalDate` now names `DateTimeFormatter` and `TemporalQuery`).

The new programs, one statement in `main`:

| Program | Bytes |
|---|---|
| `LocalDate.of(..).format(DateTimeFormatter.ofPattern("uuuu-MM-dd"))` | 322,993 |
| the same with `"EEEE, MMMM d, uuuu"` | 322,977 |
| `LocalDate.parse("2020-01-05", DateTimeFormatter.ofPattern("uuuu-MM-dd"))` | 428,993 |
| the pattern computed where the program runs | 429,389 |
| `DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(..)` | 287,903 |
| `LocalDate.parse("2020-01-05", DateTimeFormatter.ISO_LOCAL_DATE)` | 365,475 |

What the two questions the compiler answers save, measured by answering them `true`:
every ISO form made, the format by a pattern is 333,055 B and the parse 446,350; the
classes' `from` reading any value through its fields, the `until` program above is
202,041 B (the others reach no `from`).
