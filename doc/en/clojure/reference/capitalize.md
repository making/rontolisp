# clojure.string/capitalize

`(clojure.string/capitalize s)`

Answers `s` with its first character upper-cased and the rest lower-cased, each as `String`'s
`toUpperCase` and `toLowerCase` do it. Works as a function value too.

```clojure
(println (clojure.string/capitalize "hi there")) ; Hi there
(println (clojure.string/capitalize "hELLO")) ; Hello
(println (clojure.string/capitalize "ßa")) ; SSa
```
