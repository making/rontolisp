# clojure.string/upper-case

`(clojure.string/upper-case s)`

Answers `s` with every character upper-cased. Reached also as `alias/var` or a referred bare
`upper-case`; works as a function value too, so it travels through `map`.

```clojure
(println (clojure.string/upper-case "hi")) ; HI
(println (map clojure.string/upper-case ["a" "b"])) ; (A B)
```
