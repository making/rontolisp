# Clojure: `subs` and `.substring` of a double bound refuse or trap where the oracle truncates

Difficulty: Low

The oracle truncates a double bound (`(subs "abc" 1.0)`, `(.substring "abc" 1.0)` answer
`"bc"`, measured against clj 1.12.6); the refusal-family check of `%clojure-subs` leaves a
non-integer to `subseq`:

| form | interpreter | JVM | wasm |
|---|---|---|---|
| `(.substring "abc" 1.0)` | `RuntimeException` (`SUBSEQ expects an integer index, got: 1.0`) | `RuntimeException` (`the value is not of the expected type`) | trap `cast failure` |

Truncate the bound in `%clojure-subs` (and give `.substring` the same path), as `char` already
does for `(.charAt "abc" 1.5)` (the oracle's `b`). Pin in clojure-spec.
