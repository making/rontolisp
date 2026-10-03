# reader-conditional?

`(reader-conditional? x)`

`clojure.core/reader-conditional?`: `false` for every value: the reader refuses `#?`, so no reader conditional exists. The argument is still evaluated. As a value a one-argument function.

```clojure
(println (reader-conditional? '(1)))  ; false
```
