# d69. `digit-char` accepts a radix outside 2..36

Difficulty: Low

SBCL refuses a `digit-char` radix outside `(integer 2 36)` with a `type-error` (datum the radix,
expected type `(INTEGER 2 36)`), as it does `digit-char-p`'s (closed alongside `parse-integer`'s
`:radix`; `.kb/error-handling.md`, "The radix"). Here nothing checks it. Measured 2026-10-06
(SBCL 2.2.9; interpreter, JVM, P1 and component alike), the radix read at run time:

| call | SBCL | here |
|---|---|---|
| `(digit-char 5 (read-from-string "37"))` | `type-error` 37 | `#\5` |
| `(digit-char 5 (read-from-string "1"))` | `type-error` 1 | `NIL` |
| `(digit-char 35 (read-from-string "36"))` | `#\Z` | `#\Z` |

`digit-char` is the prelude defun in `LispPreludeLibrary` (`LispNames.DIGIT_CHAR`), so one check there
covers every backend; the weight is checked after the radix in SBCL's lambda list order -- measure
`(digit-char -1 37)` and `(digit-char 1.5 37)` before choosing which refusal wins. Reuse the
`(INTEGER 2 36)` report `digit-char-p` signals (`OperandTypes.RADIX_TYPE`), and pin it with a
`RadixRangeFixture` row and its ci-spec case.
