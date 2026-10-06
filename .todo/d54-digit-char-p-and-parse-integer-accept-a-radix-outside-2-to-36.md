# d54. `digit-char-p` and `parse-integer` accept a radix outside 2..36

Difficulty: Low

SBCL refuses a radix outside `(integer 2 36)` with a `type-error`. Here nothing checks it, and the
wasm digit walk reads letters past `z` as digits. The radix is read at run time
(`(read-from-string "37")`); measured 2026-10-06.

| call | SBCL | interpreter / JVM | P1 / component |
|---|---|---|---|
| `(digit-char-p #\2 37)` | `type-error` 37 | `NIL` | `2` |
| `(parse-integer "12" :radix 37)` | `type-error` 37 | `simple-error` (junk) | `39` |
| `(parse-integer "12" :radix 1)` | `type-error` 1 | `simple-error` (junk) | `simple-error` (junk) |

`parse-integer`'s expansion (`LispMacroExpander.expandParseInteger`) calls `digit-char-p` per
character, so one radix check in `digit-char-p` on every backend fixes both -- but the class
would then be `digit-char-p`'s and the check would run per character; a `parse-integer` site
spelling `:radix` may want its own check once before the scan, as its bounds have
(`.kb/sequence-bounding-keywords.md`, "Every bound is checked once"). Measure the per-character
cost before choosing. The interpreter's first-class `#'parse-integer` (`Environment`) reads
`:radix` with `asLong` and `Character.digit`, which answers -1 for any radix outside 2..36.
