# k35: `java.time`, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "java.time". Here: the probes and a
program over YAVI.

## `Grid.java`

27,197 lines under a JDK 25: 503 texts through the ten `parse` methods; 25 dates, 10
times, 28 date-times, 32 offset date-times, 30 zoned ones, 15 instants, 13 years, 12
months of a year and 17 periods through their members; every pair of each compared and
measured in every unit; all 30 fields read and set on each class; 104 zone ids; the
clocks; 400 seeded days and 400 seeded seconds. The four backends print the same lines,
all of them.

```bash
R="java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
java Grid.java > jdk.txt                                  # 2.2 s
$R Grid.java > interpreter.txt                            # 12 s
$R Grid.java -o Grid.jar && java -jar Grid.jar > jvm.txt  # 2.3 s
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt                   # 1.4 s, 959,021 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt   # 1.5 s, 966,605 B
```

A message that lists the fields a JDK could make no value of (`Unable to obtain .. {..}`)
is compared without the list (`steady`): a JDK's order changes from run to run. A region
with rules of its own (`Europe/Paris`) is no line: the library has none.

## `Zones.java`

What the regions would cost, asked of a JDK 25's `java.time.zone` (`java Zones.java`):

```
604 names, 49 of them fixed, 322 sets of rules, 18001 changes of offset, 222 yearly rules, about 122831 bytes
```

The bytes are one plain encoding (a change as the seconds since the one before it in a
variable-length number and two bytes of offset, a yearly rule in eight, a name as its
text and three bytes). Of the 49 fixed names the library knows 42: all but the seven of
`SystemV/`.

## `shelf/Loan.java`

A schema of the eight classes with YAVI's temporal constraints (`after`, `between`,
`past` and `future` of a fixed clock, a field's predicate), given texts that are and are
not one, values already of the type, and numbers for the year. Run as the schema example
of `.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/` is (a
patched copy of YAVI's sources on `--java-source-path`, one edit left): the JDK's 77 lines
on the four backends, wasm 1,256,341 B, the component 1,260,649 B, the jar 454,890 B.

## Sizes (wasm, one statement in `main`)

| Program | Bytes |
|---|---|
| `System.out.println("x")` | 1,715 |
| a `NumberFormatException` caught | 55,019 |
| `Duration.parse("PT1H").plusSeconds(5)` (before and after k35) | 155,029 |
| `LocalDate.of(2020, 2, 29).plusDays(1)` | 106,233 |
| `LocalDate.parse` | 141,218 |
| `LocalTime.parse` | 136,931 |
| `LocalDateTime.parse` | 149,073 |
| `OffsetDateTime.parse` | 156,803 |
| `ZonedDateTime.parse` | 178,759 |
| `Instant.parse` | 154,906 |
| `Year.parse` | 116,246 |
| `YearMonth.parse` | 130,526 |
| `Period.parse` | 145,990 |
| `LocalDate.now().getYear()` | 118,591 |
| `Instant.now().getEpochSecond()` | 29,535 |
| a date `plus(1, ChronoUnit.MONTHS)` | 174,641 |
| a date `get(ChronoField.DAY_OF_WEEK)` | 159,495 |

The schema example of k24 grew by 119,841 B with the table whole (1,095,450 ->
1,215,291): by `-Drontolisp.wasm.debug-func-sizes`, 69,475 B the functions of `java.time`,
14,184 B the top level (15 class objects), the rest dispatch arms and what the run time
adds.
