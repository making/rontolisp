# clojure.string/upper-case

`(clojure.string/upper-case s)`

Answers `s` upper-cased as `String.toUpperCase` does it, so one character may become several.
Reached also as `alias/var` or a referred bare `upper-case`; works as a function value too, so it
travels through `map`.

```clojure
(println (clojure.string/upper-case "hi")) ; HI
(println (map clojure.string/upper-case ["a" "b"])) ; (A B)
(println (clojure.string/upper-case "straße")) ; STRASSE
```
