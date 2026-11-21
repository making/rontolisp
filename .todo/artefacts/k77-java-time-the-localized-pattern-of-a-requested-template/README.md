# k77: the localized pattern of a requested template, measured 2026-11-21

What was learned is in `.kb/java-frontend.md`, "java.time: the localized pattern of a requested
template". Here: the two grids the library was compared on, and the sizes.

## The rule

A JDK 25 (25.0.5) was asked `getLocalizedDateTimePattern(template, ISO, locale)` in `und`,
`en-US`, `en-GB`, `en-CA`, `en` and `en-001`: every date template of bounded counts (108,864),
every time template (8,370), every date some locale resolves before every time (1,372,516),
the dates before `Bj` in `en-GB`, 2.5 million seeded and mutated templates, valid or not,
and the resolving ones again shuffled in one JVM (no answer depends on what was asked
before). A model of the rule in `.kb` gave every one of the answers. Two things to know
when asking again: sixteen threads of one JVM asking at once had some of the first
templates refused that one thread resolves, so ask with one thread per JVM, several JVMs;
and a template it has not seen costs a JDK 130 to 360 µs, one it has seen 2 to 18.

## `Grid.java`

737 lines: a bounded grid of dates, times and the two joined; refusals of the order and of
j and C; seeded templates; formatters of 31 templates written over nine values in the five
locales and read back; a formatter's refusals (null, invalid, none, in an optional part, at a
position, as a `Format`), padded, in a decimal style, built; a calendar of the probe's own of
no type and of two types, the formatter's and the value's.

```bash
R="java -cp target/classes am.ik.rontolisp.cli.RontoLispCli"   # or -jar the exec jar
java Grid.java > jdk.txt                                       # 8 s
$R Grid.java > interpreter.txt                                 # 146 s
$R Grid.java -o Grid.jar && java -jar Grid.jar > jvm.txt       # 15 s, 265,297 B
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt                 # 26 s, 636,496 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt # 26 s, 640,639 B
```

The grid holds Toronto: a JDK reads a Canadian `ET` as Toronto, which a program not holding
it reads as the region it holds that writes the name (`.kb/java-frontend.md`, "java.time: the
names in words"). The probe's own calendar is read only where it has no type: one with a
type is read in ISO's calendar here, where a JDK refuses the date that calendar did not
make (the probe's calendar answers ISO's dates).

## `Every.java`

5,899 lines: every date of each field's counts to its most (the year to six, the week to
three), every time (a period to five, every one or two hour letters, minutes and seconds to
two, every zone of v and z to four), every date some locale resolves before every time some
locale resolves, 80,000 seeded templates; the resolving ones listed, a hash per locale of all
answers.

```bash
java Every.java > jdk.txt                                      # 80 s
$R Every.java > interpreter.txt                                # 17 min
$R Every.java -o Every.jar && java -jar Every.jar > jvm.txt    # 31 s, 98,310 B
$R Every.java -o every.wasm && wasmtime run every.wasm > wasm.txt                 # 113 s, 190,387 B
$R Every.java -o every-c.wasm --component && wasmtime run every-c.wasm > comp.txt # 120 s, 194,253 B
```

## Sizes (wasm)

Before (`e979405d1`) and after, one statement in `main`:

| Program | Before | After |
|---|---|---|
| `LocalDate.parse("2020-01-05")` | 137,019 | the same bytes |
| `ZonedDateTime.of(..).plusHours(1)` in Tokyo | 154,583 | the same bytes |
| a date written by `"uuuu-MM-dd"` | 281,528 | 281,634 |
| a date read by `"uuuu-MM-dd"` | 358,746 | 358,892 |
| a pattern computed where the program runs, written and read | 449,750 | 449,982 |
| `ISO_LOCAL_DATE_TIME.format` | 251,279 | 251,361 |
| a date by `ofLocalizedDate(FormatStyle.MEDIUM)`, written, read | 286,116, 363,332 | 286,198, 363,478 |
| YAVI `Member.java`, `Booking.java` | 638,282, 1,101,024 | the same bytes |
| k43's `yavi/Event.java` (a pattern passed on) | 921,054 | 921,258 |

The new members:

| Program | Bytes |
|---|---|
| a date written by `ofLocalizedPattern("yMMMd")` | 293,334 |
| the same, read | 370,643 |
| a date written by `ofLocalizedPattern("yw")` | 320,156 |
| a template computed where the program runs, written and read | 455,387 |
| `getLocalizedDateTimePattern("yMMMd", IsoChronology.INSTANCE, Locale.UK)` | 113,704 |

Two measurements that changed the code. The formatter's locale handed to its parts in every
program made one writing by a pattern 249 B and one reading 362 B larger; it is handed only
where the part of a template is made, and the two fields are left (106 and 146 B). A template
matched with `String.indexOf(String)` and `replace(char, char)` brought 6.2 KB of the run
time (`SEARCH` 2,702 B, `%JAVA-REPLACE-CHAR` 1,907, `%SEQ-TO-LIST` 1,415; written 299,527 B):
the text is walked by characters, and a set's own skeleton hides its parent's by being
walked first. One measurement that did not: an index of where each set's first letters start,
made once, looked a time's template up on wasm in 14 µs against the walk's 130 (`yMMMEd` 108
against 132; the interpreter 0.25 ms against 1.1) and cost every program of a template
1,066 B; a formatter looks its template up once per kind of locale.
