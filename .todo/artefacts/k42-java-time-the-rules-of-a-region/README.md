# k42: the regions of the tz database, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "java.time: the regions". Here: the walk
that compares every region with a JDK, and the sizes.

## `Regions.java`

For each of the JDK's 604 zone names: its changes of offset and yearly rules; the
instants a second before, on and a nanosecond after every change, and the local
date-times on, beside and inside its gap or overlap (for the changes the yearly rules
make in five later years too); twenty seeded instants and local date-times between 1800
and 2200; the ends of the time-line; a zoned date-time moved across every fourth change
by date-based and time-based amounts and measured in every unit. A zone is one line: its
name, how many lines it was asked (362,528 in all) and their hash. `REGION=<name>` in
the environment prints the lines of one zone instead (a program's arguments do not reach
`main` on every leg).

```bash
R="java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
java Regions.java > jdk.txt                                       # 3.2 s
$R Regions.java > interpreter.txt                                 # 5.9 min
$R Regions.java -o Regions.jar && java -jar Regions.jar > jvm.txt # 15 s, 221,114 B
$R Regions.java -o regions.wasm && wasmtime run regions.wasm > wasm.txt   # 29 s, 594,888 B
$R Regions.java -o regions-c.wasm --component && wasmtime run regions-c.wasm > comp.txt   # 28 s, 599,970 B
REGION=America/Toronto java Regions.java | diff - <(REGION=America/Toronto $R Regions.java)
```

The four print the JDK's 605 lines (JDK 25.0.5, tz database 2026e). The first run
differed in five zones sharing Toronto's rules, in one field: the start of 1919-03-31,
a day whose midnight the zone skipped (`00:30`, where the gap ends).

The program asks for the names, so it holds every region. The corpus case
`a-program-holds-the-regions-of-the-tz-database-it-names` names its regions as constants
and so runs the other way a table is made.

## Sizes (wasm, one statement in `main`)

| Program | Before | After |
|---|---|---|
| YAVI `Member.java` | 663,174 | 663,174, the same bytes |
| YAVI `Booking.java` | 1,204,017 | 1,204,017, the same forms in another order |
| `ZonedDateTime.parse("2026-01-01T10:15+01:00")` | 178,760 | the same bytes |
| `OffsetDateTime.parse`, `LocalDate.parse` | 156,817, 141,206 | the same bytes |
| `LocalDate.now().getYear()` | 118,491 | 118,482, the same forms in another order |
| `ZonedDateTime.now().getOffset() != null` | 121,576 | 121,576, the same forms in another order |
| `ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).plusHours(1)` | 139,793 | the same bytes |
| the same with `ZoneId.of("Asia/Tokyo")` | refused where it runs | 190,054 |
| with `ZoneId.of("Europe/Paris")` | | 190,327 |
| with `ZoneId.of(args.length > 0 ? args[0] : "Europe/Paris")` | | 237,821 |
| `LocalDate.now(ZoneId.of("UTC")).getYear() > 2000` | 82,239 | 82,239 |
| the same with `ZoneId.of("Asia/Tokyo")` | | 124,099 |
| `ZonedDateTime.parse(..) + " " + ZoneId.getAvailableZoneIds().size()` | | 333,058 |

"The same forms in another order": the lines of `--dump-ir` are equal as multisets, or
the tokens of the lines that differ are (the arms of a dispatch, the order classes are
initialized in).

The table: 329 sets of rules for 562 names, 40,825 B of rules; a program holding all
carries 46,661 B, one naming `Asia/Tokyo` 57, `Europe/Paris` 328.
