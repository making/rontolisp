# Clojure: `subs` / `.substring` / `.charAt` of a non-number bound is the wrong class, and traps on wasm

Difficulty: Low

A double or ratio bound is truncated (`%clojure-string-bound`, `.kb/clojure-frontend.md`); any
other value is left to the verb. Measured against clj 1.12.6 with a class-reading program on a
built string:

| bound | oracle | interpreter / JVM | wasm |
|---|---|---|---|
| `nil` | `NullPointerException` | `NullPointerException` (the verb's own refusal, by luck of its message) | trap `cast failure` |
| `"a"`, `\a` | `ClassCastException` | `NullPointerException` | trap `cast failure` |
| double past the int range (`(subs s 1e10)`, `(subs s 1e20)`) | `ArithmeticException` / `IllegalArgumentException` (`subs`'s intCast; `.substring` / `.charAt` of a non-literal receiver saturate to `StringIndexOutOfBoundsException`, of a literal one match `subs`) | `StringIndexOutOfBoundsException` (clamped) | same |

Give `%clojure-string-bound` a refusal arm for a non-number (the family's `NullPointerException`
for nil, `ClassCastException` otherwise) so wasm refuses instead of trapping. Measure the size
first: the helper already costs 1.9 KB of JVM class on a program with no other numeric code, and
the exact classes of the out-of-range doubles were left out because the carriers and
`princ-to-string` added 2.3 KB more. A change the numbers say is not worth its blast radius is
not made; record the numbers in `.kb/clojure-frontend.md` either way.
