# f14. Clojure: java.nio.charset.StandardCharsets on every backend

Difficulty: Low

`(.getBytes s "UTF-8")`, `(String. bytes "UTF-8")` and `clojure.java.io`'s `:encoding` take a
charset name on every backend, and a host `Charset` by its name on the interpreter and the
JVM (`%clojure-bytes-charset`). `java.nio.charset.StandardCharsets/UTF_8` is a `java:field`,
so on wasm the program fails where it names one. Measured 2026-10-09:

| program | interpreter | wasm P1 |
|---|---|---|
| `(prn (vec (.getBytes "é" java.nio.charset.StandardCharsets/UTF_8)))` | `[-61 -87]` | `Unhandled condition: The function JAVA:FIELD is undefined` |

## What decides the design

- The six `StandardCharsets` fields, and `Charset/forName` of a literal, as a value standing
  for the charset on every backend: printed and named (`.name`, `str`) like the host
  `Charset`, read by `%clojure-bytes-charset` and `%clojure-io-charset`, crossing into a
  `java:` member as the host object where there is one.
- A program naming none of them keeps the lowering it has.

## Plan

1. clojure-spec cases over `.getBytes`, `String.`, `slurp :encoding` and `io/reader` with a
   `StandardCharsets` field, on the four backends.
2. The value, its printer and the two charset readers.
3. `doc/*/clojure/reference/clojure-java-io.md`, `byte-array.md`.
