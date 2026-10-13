# parse-long

`(parse-long s)`

Parses decimal digits with an optional leading `-`/`+` and answers a `Long` value, or
`nil` when the parse fails: the oracle's `Long/valueOf` grammar, without host calls, so
it runs on every backend (a portable program had no number parser but `read-string`).
Digits may be ASCII or any Unicode decimal digit; past the long range, or with any other
spelling, the answer is `nil`. A non-string is the oracle's `IllegalArgumentException`
(`Expected string, got ...`). As a value a one-argument function.

```clojure
(println (parse-long "12") (parse-long "-12") (parse-long "+12")) ; 12 -12 12
(println (parse-long "007") (parse-long "9223372036854775807")) ; 7 9223372036854775807
(println (map parse-long ["12" "xx" "9223372036854775808"])) ; (12 nil nil)
```
