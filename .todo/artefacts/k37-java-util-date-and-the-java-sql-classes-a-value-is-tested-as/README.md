# k37: `java.util.Date` and `java.sql`, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "java.util.Date and java.sql". Here: the
probe and a program over YAVI.

## `Grid.java`

2,095 lines under a JDK 25: 378 instants (38 chosen around the calendar's change, year 1,
year 10,000 and the long's ends, 340 seeded) through every member of the four classes;
73 texts of `java.sql.Date.valueOf`, 40 of `Time.valueOf`, 124 of `Timestamp.valueOf`;
`java.time` values converted across 1582 and year 0; 300 seeded date-times and 100
times; `from`, `equals`, `compareTo`, `before`, `after` and `hashCode` of the classes
against each other.

```bash
R="java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
TZ=UTC java Grid.java > jdk.txt
TZ=UTC $R Grid.java > interpreter.txt                                  # 3.6 s
$R Grid.java -o grid.jar && TZ=UTC java -jar grid.jar > jvm.txt
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt        # 408,099 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt
```

Under `TZ=UTC` the interpreter and the JVM print the JDK's lines, all of them; wasm and
the component all but the 12 of a non-ASCII digit (`deviations.md`). Under
`TZ=Etc/GMT-9`, all of them. Under `TZ=America/New_York` (wasm with
`--env TZ=.. --dir /usr/share/zoneinfo`) they differ from a JDK in the zone's name
(`GMT-05:00` for `EST`), before 1883 (the host's local mean time) and past the year
275,760, nowhere else.

## `Gap.java`

`Timestamp.valueOf` of the wall times New York, London, Sydney and Lord Howe skip and
show twice in 2026: under each `TZ` the interpreter prints a JDK's lines.

## `shelf/Row.java`

A row of `java.sql` values, as a JDBC driver hands it, read by a schema of the
`java.time` classes they stand for (`UntypedInput.SqlTypes`), with a `java.util.Date` and
values given for a class they are no column of. With YAVI's unpatched coordinates:

```bash
(TZ=UTC java -cp yavi-0.18.0.jar shelf/Row.java)
(TZ=UTC $R --java-source-dep am.ik.yavi:yavi:0.18.0 shelf/Row.java -o row.wasm)
```

The JDK's 28 lines on the four backends: wasm 1,272,912 B, the component 1,279,403 B,
the jar 425,073 B.
