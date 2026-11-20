# k52: what the formatter of `java.time` left, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "java.time: what the formatter left". Here:
the grid the library was compared on, and the sizes.

## `Grid.java`

4,561 lines under a JDK 25 (25.0.5), fourteen probes: the ISO calendar and its eras
(`Chrono`); `MonthDay`, `OffsetTime` and `InstantSource` (`Smaller`); the adjusters,
`datesUntil`, `timeLineOrder` and `ChronoPeriod` (`Adjust`); every field of `IsoFields`,
`JulianFields` and nine `WeekFields` read, ranged and set over 19 dates, on the other
classes and as units (`Fields`, `Fields2`), and all 49 `WeekFields` over 382 seeded dates
as hashes (`Seeded`); runs of numbers read strictly and leniently, 1,760 lines (`Runs`);
the pattern letters of weeks, quarters and the modified Julian day written in the five
locales, with their texts and refusals (`Letters`), read in every resolver style
(`Resolve`, `Mixed`) and by width and sign (`Widths`); fields of the program's own
resolved by a formatter (`Owned`, `Forever`); the display names (`Names`). The four
backends print the JDK's lines, all of them; the JDK prints the same under
`LANG=C.UTF-8` and another `TZ`.

```bash
R="java -cp target/classes am.ik.rontolisp.cli.RontoLispCli"   # or -jar the exec jar
java Grid.java > jdk.txt                                       # 2.4 s
$R Grid.java > interpreter.txt                                 # 26 s
$R Grid.java -o Grid.jar && java -jar Grid.jar > jvm.txt       # 2.6 s, 373,309 B
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt                 # 0.9 s, 1,172,798 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt # 1.2 s, 1,177,449 B
```

Not asked, since a JDK answers it in an order of its run (a hash map of fields hashed by
identity): which of two fields that are no `ChronoField` is resolved first -- two of a
program's own, a Julian day and a modified one, the day of the week of two `WeekFields`,
a day of the week by its name and by its number of the locale's week --, and which time
field of a conflict is named first. A field map a JDK prints is printed sorted, and the
refusal of a text whose fields make no value without its list. `IsoChronology.hashCode`
is the identity of a class under a JDK (it changes with `-Xshare:off`).

## Sizes (wasm)

Before (`3b8d4a972`) and after:

| Program | Before | After |
|---|---|---|
| YAVI `Member.java` | 663,174 | the same bytes |
| YAVI `Booking.java` | 1,204,095 | 1,204,554 (`Period` is a `ChronoPeriod`: its class object) |
| k35 `Grid.java`, k42 `Regions.java` | 1,072,291, 599,290 | 1,072,648, 599,559 |
| k43 `Grid.java` (patterns computed where it runs) | 1,124,284 | 1,197,986 |
| `LocalDate.parse`, `Duration.parse` .. `ZonedDateTime.parse` (ten programs) | | the same bytes or 20 fewer |
| `LocalDate.of(..).plusDays(1)`, `.plus(1, MONTHS)`, `ZonedDateTime.of(..)` in UTC, in Tokyo | | the same bytes |
| a date written by a pattern, `"EEEE, MMMM d, uuuu"` | 323,000, 323,002 | 324,879, 324,856 |
| a date read by a pattern | 428,993 | 431,456 |
| the pattern computed where the program runs | 429,380 | 495,505 |
| k43's `yavi/Event.java` (YAVI's `isoLocalDate()`: its pattern passed on) | 946,257 | 1,020,590 |
| `ISO_LOCAL_DATE_TIME.format`, `LocalDate.parse(.., ISO_LOCAL_DATE)` | 287,914, 365,483 | 284,599, 362,163 |

The new programs, one statement in `main`:

| Program | Bytes |
|---|---|
| `MonthDay.parse("--12-03")` | 153,734 |
| `OffsetTime.parse("10:15+01:00")` | 145,010 |
| a date `with(TemporalAdjusters.lastDayOfMonth())` | 179,734 |
| a date's `datesUntil(..).count()` | 116,258 |
| a date's `query(TemporalQueries.chronology())` | 183,767 |
| a date's `get(IsoFields.QUARTER_OF_YEAR)` | 176,783 |
| a date's `get(WeekFields.ISO.weekOfWeekBasedYear())` | 198,137 |
| a date written by `"YYYY-ww-e"` | 370,960 |
| a date read by `"YYYY-ww-e"` | 491,365 |
| a date read by `ISO_WEEK_DATE` | 402,801 |

Of the 1,879 B a pattern program grew by: the run model of numbers (a run's first ended
and its followers as virtual copies, a fraction of fixed width one of a run) and the
calendar a formatter holds. A program reading an ISO form shrank by 3.3 KB: the builder
made a reduced number in every program to copy a run's follower, which a virtual copy
leaves to the programs appending one. A computed pattern can name every letter, so it
holds the parts of weeks, quarters and Julian days and the resolving of their fields
(+66 KB: `WeekFields` 21.4 KB, `IsoFields` 12.6 KB, the top level 29.6 KB).
