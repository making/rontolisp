# clojure.string/lower-case

`(clojure.string/lower-case s)`

Answers `s` lower-cased as `String.toLowerCase` does it, so a capital sigma ending a word becomes
`ς`. Reached also as `alias/var` or a referred bare `lower-case`; works as a function value too.

```clojure
(println (clojure.string/lower-case "HI")) ; hi
(println (clojure.string/lower-case "ΟΔΥΣΣΕΥΣ")) ; οδυσσευς
```
