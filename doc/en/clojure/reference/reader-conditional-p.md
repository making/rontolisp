# reader-conditional?

`(reader-conditional? x)`

`clojure.core/reader-conditional?`: `false` for every value: a reader conditional is read into its branch, and `{:read-cond :preserve}`, which would build one, is refused, so none exists. The argument is still evaluated. As a value a one-argument function.

```clojure
(println (reader-conditional? '(1)))  ; false
```
