# clojure.string/replace

`(clojure.string/replace s match replacement)`

Answers `s` with every occurrence of the literal string or character `match` replaced by the
string or character `replacement` -- regex patterns are refused, there is no regex runtime.
Reached also as `alias/var` or a referred bare `replace`; works as a function value too.

Deviation: `match` is a literal string or character, never a regex pattern.

```clojure
(println (clojure.string/replace "aaa" "a" "b")) ; bbb
(println (clojure.string/replace "aaa" \a \b)) ; bbb
(println (clojure.string/replace "aaa" "." "b")) ; aaa
```
