# k57: the localized forms, a zone's name in words and a day's period, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "java.time: the names in words, the localized
forms and a day's periods". Here: the grid the library was compared on, and the sizes.

## `Grid.java`

2,350 lines under a JDK 25 (25.0.5), four probes: the localized forms of every pair of
styles in the five locales, their patterns, written over 18 values and read back in each
resolver style (`Styles`); the names of 40 regions and 26 fixed zones in words at seven
instants, `getDisplayName` in every style, the values that have no instant or no date-time,
every name read back in short and in full, 60 odd texts read as `z`, `zzzz` and `VV` with
and without case, names read in an overlap and with preferred zones (`Names`); the periods
of a day written minute by minute, their words read in each mode, and read with the hours
of half a day and of the day, a minute and a second in each resolver style (`Periods`);
`localizedBy` of six formatters (`By`). The four backends print the JDK's lines, all of them;
the JDK prints the same under `LANG=C.UTF-8` and another `TZ`.

```bash
R="java -cp target/classes am.ik.rontolisp.cli.RontoLispCli"   # or -jar the exec jar
java Grid.java > jdk.txt                                       # 2.5 s
$R Grid.java > interpreter.txt                                 # 43 s
$R Grid.java -o Grid.jar && java -jar Grid.jar > jvm.txt       # 4.5 s, 381,962 B
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt                 # 2.7 s, 1,276,913 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt # 2.8 s, 1,281,186 B
```

`ENGLISH` comes last among the locales: a JDK that has written a zone's names for
`ENGLISH` writes some of that zone's names for `UK` and `CANADA` as `ENGLISH` has them from
then on (148 zones), and the library writes the names a JDK writes before that. A field
map a JDK prints is printed sorted (its order is a hash map's of identity hashes).

## Sizes (wasm)

Before (`e9313f180`) and after:

| Program | Before | After |
|---|---|---|
| YAVI `Member.java`, `Booking.java` | 663,174, 1,204,473 | the same bytes |
| k35 `Grid.java` | 1,072,648 | the same bytes |
| k42 `Regions.java` (every region) | 599,559 | 602,696 |
| k43 `Grid.java` (patterns computed, every region) | 1,197,986 | 1,371,635 |
| k52 `Grid.java` (patterns computed) | 1,172,798 | 1,218,365 |
| k43's `yavi/Event.java` (a pattern passed on) | 1,020,590 | 1,057,686 |
| `LocalDate.parse("2020-01-05")` | 141,195 | the same bytes |
| `ZonedDateTime.of(..).plusHours(1)` in UTC, in Tokyo | 139,790, 196,687 | the same bytes |
| a date written by `"uuuu-MM-dd"`, by `"EEEE, MMMM d, uuuu"` | 324,823, 324,838 | 326,700, 326,708 |
| a date read by `"uuuu-MM-dd"` | 431,410 | 437,758 |
| the pattern computed where the program runs | 495,472 | 532,923 |
| `ISO_LOCAL_DATE_TIME.format`, `LocalDate.parse(.., ISO_LOCAL_DATE)` | 284,567, 362,116 | 286,144, 364,662 |
| `ZonedDateTime.parse(.., ISO_ZONED_DATE_TIME)` of a text in Tokyo | 452,981 | 464,645 |

The new programs, one statement in `main`:

| Program | Bytes |
|---|---|
| a date by `ofLocalizedDate(FormatStyle.MEDIUM)`, written | 332,031 |
| the same, read | 442,946 |
| a zoned date-time in Tokyo by `ofLocalizedDateTime(FormatStyle.LONG)` | 416,346 |
| a zoned date-time in Tokyo by `"uuuu-MM-dd HH:mm z"`, written | 409,986 |
| the same, read | 527,968 |
| `ZoneId.of("Asia/Tokyo").getDisplayName(TextStyle.FULL, Locale.US)` | 343,931 |
| a zoned date-time by `"... zzzz"` in a zone computed where it runs (every region) | 548,248 |
| a time by `"h:mm B"`, written | 330,931 |
| the same, read | 451,934 |

A program writing by a pattern grew by the letters `z`, `v` and `B` the pattern switch now
makes behind its questions and by the formatter's calendar in a refusal's text (`Writing`,
1.9 KB); one reading by a pattern by a zone's id looked up once per reading among the names
the program has, in either case (6.3 KB). A program holding every region and writing or
reading a zone's name in words carries every region's names: 90,405 B written and 26,798 B
read (the k43 grid). Of the 35.7 KB of functions a computed pattern grew by, a zone's name in
words (`ZoneNames`, `Step.ZoneText`) is 20.6 KB and a period of the day (its part, its
words, its resolving) 12.2 KB; the localized forms are in no such program.

Two measurements that changed the code. Each failed lookup of a zone's id on a reading cost
850 µs of wasm with 40 regions held (`ZoneId.region` reads the program's table at a name it
lacks), and a reading tried every length of the text: the grid's names took 18 s on wasm,
1.75 s once the ids are looked through once per reading. A `HashMap` in the library's names
put every class's `hashCode` and `equals` in a program writing a zone's name (49 KB); the
names are arrays looked through.
