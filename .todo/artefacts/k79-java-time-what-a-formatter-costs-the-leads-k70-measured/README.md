# k79: what a formatter costs, measured 2026-11-21

What was learned is in `.kb/java-frontend.md`, "java.time: what a formatter costs". Here: the
programs the sizes are of.

Each is `import java.time.*; import java.time.format.*;` (and `java.time.temporal.ChronoField`)
around one `main` of the statement given, compiled to wasm:

```bash
R="java -cp target/classes am.ik.rontolisp.cli.RontoLispCli"
$R P04.java -o p04.wasm && stat -c %s p04.wasm
$R yavi/Event.java --java-source-dep am.ik.yavi:yavi:0.18.0 -o event.wasm   # k43's artefact
```

| Program | `main` | Base `42b66b2e6` | k79 |
|---|---|---|---|
| P01 | `LocalDate.parse("2020-01-05")` | 137,022 | the same bytes |
| P02 | `ZonedDateTime.of(.., ZoneId.of("Asia/Tokyo")).plusHours(1)` | 154,592 | the same bytes |
| P03 | `LocalDate.of(..).plusDays(1)` | 97,222 | the same bytes |
| P04 | a date written by `ofPattern("uuuu-MM-dd")` | 281,671 | 254,076 |
| P05 | a date read by it | 358,978 | 315,477 |
| P06 | `ofPattern(args.length > 5 ? args[0] : "uuuu-MM-dd")`, written and read | 450,150 | 449,964 |
| P07 | `ISO_LOCAL_DATE_TIME.format` | 251,321 | the same bytes |
| P08 | `ofLocalizedDate(FormatStyle.MEDIUM)`, written and read | 382,873 | 381,815 |
| P09 | `ZonedDateTime.parse(.., ISO_ZONED_DATE_TIME)` in Tokyo | 373,377 | 372,270 |
| P10 | P05 `.withResolverStyle(ResolverStyle.STRICT)` | 359,645 | 315,816 |
| P11 | P05 `.withResolverStyle(ResolverStyle.LENIENT)` of `2020-01-35` | 359,645 | 317,233 |
| P12 | `zzzz` of `ZoneId.of(args.length > 5 ? args[0] : "Asia/Tokyo")` (every region) | 474,188 | 412,324 |
| P13 | `uuuu-MM-dd HH:mm zzzz` of P12's zone, written and read | 606,200 | 544,593 |
| P14 | `h:mm B`, written and read | 387,599 | 339,699 |
| P15 | P13 of `ZoneId.of("Asia/Tokyo")` (reading names holds every region) | 605,736 | 544,212 |
| P16 | `zzzz` of `ZoneId.of("Asia/Tokyo")` | 336,223 | 310,944 |
| P17 | a date written by the builder's `appendValue`s and literals | 242,116 | the same bytes |
| P18 | a date read by them | 304,634 | 303,530 |
| P19 | `static ZonedDateTime at(String zone)` of `ZoneId.of(zone)`, called with Tokyo and Paris | 201,377 | 155,039 |
| P20 | `static String written(String pattern, ..)` of `ofPattern(pattern)`, called through a second with `YYYY 'w'ww e` | 325,247 | 282,618 |
| Event | k43's `yavi/Event.java` (YAVI's private `isLocalDatePattern(pattern)`) | 921,352 | 799,945 |
| Member, Booking | `examples/java/yavi/` | 638,295, 1,101,041 | the same bytes |

Each lead alone, measured on the way: P05 358,978 -> 357,819 by the lenient branches behind
their question, P10 -> 358,257 by `STRICT` no longer answering it; `Event` 920,178 -> 848,308 by
the constant passed on (the same bytes as naming `uuuu-MM-dd` for every computed pattern,
the bound measured first); P12 474,188 -> 438,130 by the groups of names (P16 +517); P04 and
P05 by the letters no pattern names, gated as an experiment first: 251,598 and 312,997.
