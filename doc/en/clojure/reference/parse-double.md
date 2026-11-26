# parse-double

`(parse-double s)`

Parses a string with floating point components and answers a `Double` value, or `nil`
when the parse fails: the oracle's `Double/valueOf` grammar, without host calls, so it
runs on every backend. Surrounding whitespace is stripped, then an optional sign and
`NaN`, `Infinity`, a decimal or a hex float with an optional `d`/`D`/`f`/`F` suffix
(which never rounds through float: `0.1f` is `0.1`). A non-string is the oracle's
`IllegalArgumentException` (`Expected string, got ...`). As a value a one-argument
function.

```clojure
(println (parse-double "1.5") (parse-double "1e10") (parse-double "0x1.0p3")) ; 1.5 1.0E10 8.0
(println (parse-double "NaN") (parse-double "-Infinity")) ; ##NaN ##-Inf
(println (map parse-double ["1.5" "xx" "0x10"])) ; (1.5 nil nil)
```
