# Scheme conformance bugs found while writing the reference

Difficulty: Medium

Writing one page per Scheme name (`doc/<lang>/scheme/reference/`) turned up these, each
checked against Gauche 0.9.15 (`gosh -r7`) on 2026-09-18. None is documented as a feature;
where a page states the current behavior as a deviation, fix the page with the code.
Each fix starts with a failing test (`SchemeBuiltinsTest`, `SchemeLoweringTest` or a
`scheme-spec.yaml` case when the backends can differ).

| Name | Now | R7RS / gosh |
|---|---|---|
| `quotient`, `truncate-quotient`, `floor-quotient` | `(quotient 7.0 2)` is `3` | `3.0` (the remainders already stay inexact) |
| `rational?` | `(rational? (/ 1 0.0))` is `#t` | `#f` for infinities and NaN |
| `number->string` | `(number->string 1/3 2)` is `"1/3"` | `"1/11"` |
| `gcd`, `lcm` | `(gcd 2.0 4)` is an error | `2.0` (the page states the refusal) |
| `odd?`, `even?` | `(odd? 1.5)` is `#t` | an error |
| `case` | `(case "a" (("a") 'string) (else 'no))` is `string` | `no` (`eqv?`; `(eqv? "abc" "abc")` on two literals is also `#t`) |
| `list-copy` | `(list-copy '(1 2 . 3))` is `(1 2)` | `(1 2 . 3)` |
| `list?` | never returns on a circular list | `#f` |
| `reduce` (SICP) | `(reduce - 0 '(1 2 3 4))` is `-8` | `2` (SRFI-1 / MIT call `(f elem acc)`) |
| `fold-left`, `fold-right` (SICP) | one list only | several lists (the pages state it) |
| `string`, `list->string` | `(string #\a 1)` is `"a1"` | an error |
| `stream-car` (SICP) | `(stream-car '())` is `()` | an error |
| record modifier | `(set-point-x! p 10)` answers `10`, which the REPL echoes | unspecified (the `define-record-type` page states it) |
