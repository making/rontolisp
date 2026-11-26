# clojure.string/triml

`(clojure.string/triml s)`

Answers `s` with whitespace trimmed from the left only.

```clojure
(println (str "[" (clojure.string/triml "  hi  ") "]")) ; [hi  ]
```
