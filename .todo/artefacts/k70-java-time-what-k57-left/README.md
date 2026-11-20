# k70: what k57 left of java.time, measured 2026-11-21

What was learned is in `.kb/java-frontend.md`, "java.time: what k57 left". Here: the grid the
library was compared on, and the sizes.

## `Grid.java`

998 lines under a JDK 25 (25.0.5), five probes: a formatter's decimal style, its members
and every part written and read in two styles of the probe's own, by pattern, ISO form and
builder, strictly, leniently and ignoring case (`Decimals`); `ParsePosition`, `FieldPosition`,
`ParseException`, a `Format` of the probe's own, a formatter as a `Format` and its reading
from a position, resolved or not, over texts and starting indexes inside, at and past the
text, with error indexes already set (`Positions`); the fields a formatter resolves
(`Resolving`); `resolveDate` over 48 maps of fields in each resolver style in ISO's calendar
and in a calendar of the probe's own over ISO's dates, the lenient day of a week in an aligned
week over a grid of years, months, weeks and days as one hash per year (136,518 maps and 3,762
texts), and that calendar's defaults (`Calendars`); the era read in five locales and three
modes, a localized offset read from 33 texts in each style and mode, with a pad, after a
literal and optional, a fraction of no fixed width after a run in seven builders, the null
refusals of 70 members and the equality and hash codes of 26 clocks (`Left`). The four
backends print the JDK's lines, all of them; the JDK prints the same under `LANG=C.UTF-8` and
another `TZ`.

```bash
R="java -cp target/classes am.ik.rontolisp.cli.RontoLispCli"   # or -jar the exec jar
java Grid.java > jdk.txt                                       # 3 s
$R Grid.java > interpreter.txt                                 # 85 s
$R Grid.java -o Grid.jar && java -jar Grid.jar > jvm.txt       # 6 s, 379,403 B
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt                 # 5 s, 1,084,939 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt # 5 s, 1,089,475 B
```

A field map a JDK prints is printed sorted (its order is a hash map's of identity hashes),
and so is a set of resolver fields; `DecimalStyle.getAvailableLocales()` is asked whether it
holds each locale, not its size (a JDK's has 1,158).

## Sizes (wasm)

Before (`358f6b493`) and after (`ca6c72ecb`, before merging k64, whose arrays made the grid
1,009,815 B), one statement in `main`:

| Program | Before | After |
|---|---|---|
| `LocalDate.parse("2020-01-05")` | 138,518 | the same bytes |
| `ZonedDateTime.of(..).plusHours(1)` in UTC, in Tokyo | 130,554, 172,832 | the same bytes |
| `LocalDate.of(..).plusDays(1)` | 100,909 | the same bytes |
| a date written by `"uuuu-MM-dd"` | 306,048 | 304,922 |
| a date read by `"uuuu-MM-dd"` | 403,287 | 402,498 |
| a pattern computed where the program runs, written and read | 508,946 | 512,488 |
| `ISO_LOCAL_DATE_TIME.format` | 270,570 | 271,385 |
| `LocalDate.parse(.., ISO_LOCAL_DATE)` | 341,894 | 342,408 |
| `ZonedDateTime.parse(.., ISO_ZONED_DATE_TIME)` of a text in Tokyo | 421,883 | 423,262 |
| a date by `ofLocalizedDate(FormatStyle.MEDIUM)`, written, read | 310,806, 408,085 | 309,541, 407,152 |
| Tokyo by `"uuuu-MM-dd HH:mm z"`, written, read | 372,161, 488,949 | 370,804, 488,465 |
| a time by `"h:mm B"`, written, read | 309,267, 415,087 | 307,889, 414,049 |
| YAVI `Member.java`, `Booking.java` | 648,452, 1,134,067 | 648,473, 1,134,090 |
| k43's `yavi/Event.java` (a pattern passed on) | 989,578 | 994,489 |

The new members:

| Program | Bytes |
|---|---|
| a date written in a decimal style of the program's | 309,258 |
| a date written by `toFormat()` | 320,157 |
| a date read from a `ParsePosition` | 402,793 |
| a date read by a formatter of `withResolverFields(YEAR)` | 415,823 |

Three measurements that changed the code. `Parsed` resolving a date through
`IsoChronology.resolveDate` over a map view of its arrays, as a JDK's does, made a program
reading a date by a pattern 56,077 B larger (459,364): `Parsed` keeps its own copy over the
arrays. Holding the resolver fields as a `HashSet` put every class's `hashCode` and `equals`
in a program naming them (449,446): they are an array, the set made where it is asked for.
`GmtOffset`, which reading a full localized offset as a JDK does made 1 KB larger, was in
every program of a pattern: it is made only where a pattern can name `O` or `Z`, and a date
written by a pattern is 1.1 KB smaller than before.
