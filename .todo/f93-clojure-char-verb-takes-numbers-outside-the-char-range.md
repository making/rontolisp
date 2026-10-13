# f93. Clojure: the `char` verb takes numbers outside the char range

Difficulty: Easy

Found while closing f76 (2026-10-13): the oracle's `charCast` refuses a
number outside `0..65535` (`(char 70000)` is `IllegalArgumentException`
`Value out of range for char: 70000`, measured clj 1.12.6), but
`rontolisp::%clojure-char` truncates without a range check, so `(char 70000)`
answers a character here. f76's `%clojure-char-cast` already implements the
oracle's check for `aset-char` (a double is refused past its edges, any other
number truncated first, `NaN` refused as `Value out of range for char: NaN`).

## Plan

1. Route `char` through the range check (or fold it into `%clojure-char`),
   keeping `char-array`'s constructor semantics as they are.
2. clojure-spec row, oracle-identical, four backends.
3. User docs en + ja.
