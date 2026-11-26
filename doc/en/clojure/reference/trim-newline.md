# clojure.string/trim-newline

`(clojure.string/trim-newline s)`

Answers `s` with trailing newline characters (`\n`, `\r`) stripped -- whitespace other than
line breaks stays.

```clojure
(println (clojure.string/trim-newline "a\r\n")) ; a
```
