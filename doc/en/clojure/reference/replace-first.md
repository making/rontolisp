# clojure.string/replace-first

`(clojure.string/replace-first s match replacement)`

Answers `s` with only the first occurrence of the literal string or character `match` replaced.
Works as a function value too.

Deviation: `match` is a literal string or character, never a regex pattern.

```clojure
(println (clojure.string/replace-first "aaa" "a" "b")) ; baa
(println (clojure.string/replace-first "aaa" "a+" "b")) ; aaa
```
