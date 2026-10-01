# clojure.string/re-quote-replacement

`(clojure.string/re-quote-replacement s)`

Answers `s` as-is. In the literal world there is nothing to quote: replacement strings are
already literal, so the quoting the oracle performs for regex replacements is the identity.

```clojure
(println (clojure.string/re-quote-replacement "a$b")) ; a$b
(println (clojure.string/re-quote-replacement "$1")) ; $1
```
