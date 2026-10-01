# clojure.string/re-quote-replacement

`(clojure.string/re-quote-replacement s)`

Answers `s` with every `\` and `$` quoted, so the answer replaces literally
inside a pattern replacement (where `\` quotes and `$n` reads a group).

```clojure
(println (clojure.string/re-quote-replacement "a$b")) ; a\$b
(println (clojure.string/re-quote-replacement "$1")) ; \$1
(println (clojure.string/replace "abc" #"(b)" (clojure.string/re-quote-replacement "$1"))) ; a$1c
```
