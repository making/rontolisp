# clojure.string/blank?

`(clojure.string/blank? s)`

Answers whether `s` is empty, `nil`, or whitespace only. `nil` answers `true`, like the oracle.
Works as a function value too.

```clojure
(println (clojure.string/blank? "  ")) ; true
(println (clojure.string/blank? nil)) ; true
(println (clojure.string/blank? "x")) ; false
```
