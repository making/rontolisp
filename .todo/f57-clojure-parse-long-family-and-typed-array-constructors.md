# f57. Clojure: `parse-long`/`parse-double`/`parse-boolean` and the array constructors

Difficulty: Medium

Found while writing `examples/clojure/` (2026-10-12): Clojure 1.11's portable parsers and the
core array constructors are `unknown name` on every backend.

| program | clj 1.12.6 | here |
|---|---|---|
| `(parse-long "12")`, `(parse-double "1.5")`, `(parse-boolean "true")` | `12`, `1.5`, `true` (nil for a malformed string) | `unknown name` |
| `(object-array [1 2])`, `(to-array [1 2])`, `(into-array String ["a"])` | `Object[]`, `String[]` | `unknown name` |
| `(int-array [1 2])`, `(long-array 3)`, `double-array`, `float-array`, `short-array`, `char-array`, `boolean-array` | primitive arrays | `unknown name` |

The parsers matter most on wasm, where `Long/parseLong`, `Integer/parseInt`, `Double/parseDouble`,
`Math/abs` and `Math/round` are `The function JAVA:STATIC is undefined` at run time (no host),
so a portable program has no number parser but `read-string`. `deps-project/units/money.cljc`
folds digits under a `:rontolisp` branch for want of one.

## What decides the design

- The parsers are the oracle's regex-free scanners (`Long/valueOf` grammar for `parse-long`:
  optional sign, decimal digits, no `_`/radix; `parse-double` Java's `Double/valueOf` grammar,
  `NaN`/`Infinity` included; `parse-boolean` exactly `"true"`/`"false"`), and a non-string is
  the oracle's `IllegalArgumentException`.
- Arrays: `make-array` makes a vector here and a Java array a member answers is a list
  (`deviations.md`); `byte-array` is a real array family (`.kb/clojure-frontend.md` "Byte
  arrays"). Decide whether the typed constructors join the byte-array family's representation
  (`aget`/`aset`/`alength`, `java:` crossing as the Java array) or the vector stand-in, and
  measure what each costs a program that names none.

## Plan

1. clojure-spec rows, oracle-identical, four backends; the parsers first (smallest, and the
   wasm gap).
2. The array constructors per the decision above; `aget`/`aset`/`alength`/`aclone` over them.
3. User docs en + ja; replace the digit fold in `examples/clojure/deps-project/units/src/units/money.cljc`
   with `parse-long`.
