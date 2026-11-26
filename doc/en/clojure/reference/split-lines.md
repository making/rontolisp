# clojure.string/split-lines

`(clojure.string/split-lines s)`

Splits `s` around line breaks (`\n`, `\r\n`), answering a seq of the parts. A literal split over
the line-break separators -- no regex runtime. Works as a function value too.

```clojure
(println (clojure.string/split-lines "a\nb")) ; (a b)
(println (clojure.string/split-lines "a\r\nb")) ; (a b)
```
