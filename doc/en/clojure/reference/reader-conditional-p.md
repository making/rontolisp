# reader-conditional?

`(reader-conditional? x)`

`clojure.core/reader-conditional?`: whether `x` is a [reader-conditional](reader-conditional.md), which only `{:read-cond :preserve}` and the constructor make (a reader conditional in source or under `:allow` reads into its branch). As a value a one-argument function.

```clojure
(println (reader-conditional? (read-string {:read-cond :preserve} "#?(:clj 1)")))  ; true
(println (reader-conditional? '(1)))  ; false
```
