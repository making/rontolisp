# clojure.string/split

`(clojure.string/split s sep)`
`(clojure.string/split s sep limit)`

Splits `s` around the literal string `sep` -- regex patterns are refused, there is no regex
runtime. A positive `limit` caps the part count, the last part holding the rest; a negative one
keeps every part; otherwise trailing empty parts drop. An empty input answers `nil`. Reached
also as `alias/var` or a referred bare `split`; works as a function value too.

Deviation: the pattern is a literal string, never a regex.

```clojure
(println (clojure.string/split "a,b" ",")) ; (a b)
(println (clojure.string/split "a,b," "," -1)) ; (a b )
(println (clojure.string/split "aaa" ".")) ; (aaa)
```
