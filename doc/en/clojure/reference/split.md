# clojure.string/split

`(clojure.string/split s sep)`
`(clojure.string/split s sep limit)`

Splits `s` around `sep`: a pattern value (`#"..."`, `re-pattern`) splits around
matches, a literal string or character splits literally. A positive `limit` caps
the part count, the last part holding the rest; a negative one keeps every part;
otherwise trailing empty parts drop. An empty input answers `nil` for a literal
separator and one empty part for a pattern (like the oracle). Reached also as
`alias/var` or a referred bare `split`; works as a function value too.

Deviation: the answer is a seq, never a vector; string separators stay literal,
never patterns.

```clojure
(println (clojure.string/split "a,b" ",")) ; (a b)
(println (clojure.string/split "a,b," "," -1)) ; (a b )
(println (clojure.string/split "aaa" ".")) ; (aaa)
(println (clojure.string/split "a,b" #",")) ; (a b)
(println (clojure.string/split "a b  c" #"\s+")) ; (a b c)
```
