# k31: `java.math`, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "java.math". Here: the probe and a
program over YAVI.

## `Grid.java`

94,160 lines under a JDK 25: 20 integers and 39 decimals, every pair through every
operation, each rounding mode at five scales and four precisions, ten math contexts, 98
texts a number is and is not read from, 31 pairs of numbers far apart, 150 seeded
integers and 250 seeded decimals. The four backends print the same lines, all of them.

```bash
R="java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
java Grid.java > jdk.txt                                  # 1.8 s
$R Grid.java > interpreter.txt                            # 17 s
$R Grid.java -o Grid.jar && java -jar Grid.jar > jvm.txt  # 2.5 s
$R Grid.java -o grid.wasm && wasmtime run grid.wasm > wasm.txt                   # 14.6 s, 404,562 B
$R Grid.java -o grid-c.wasm --component && wasmtime run grid-c.wasm > comp.txt   # 15.3 s
```

`ByZero.java` (224 divisions by zero at a scale: the two wordings) and `Messages.java`
(what a `MessageFormat` writes for 36 decimals and 7 integers) print a JDK's lines on
the four backends too. Asked of the JDK alone: digits outside ASCII (`"١٢٣"`, read on
the interpreter and the JVM and not on wasm, as `Integer.parseInt` reads them) and a
null argument (a `NullPointerException` with a JDK's own words about the field it read).

## `shelf/Price.java`

A schema of a `BigDecimal` and a `BigInteger` with bounds, given numbers of other types,
texts and numbers out of range. Run as the schema example of
`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/` is (a
patched copy of YAVI's sources on `--java-source-path`): the JDK's 19 lines on the four
backends, wasm 1,034,348 B, the component 1,038,450 B, the jar 387,726 B.

## A tagged value against Java source holding the integer

20,000,000 sums of a 20-digit step, best of three, seconds:

| | wasm | JVM |
|---|---|---|
| `(n . :big)`, a cons made in Lisp | 2.08 | 1.15 |
| a structure made in Lisp | 3.37 | 1.30 |
| `BigInteger.add` of the library | 2.92 | 1.13 |
| a JDK's `BigInteger.add` | | 1.18 |
