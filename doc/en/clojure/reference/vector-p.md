# vector?

`(vector? x)`

Answers whether `x` is a vector, `T`-or-false. A list is not a vector.

```clojure
(println (vector? [1]))  ; true
(println (vector? '(1))) ; false
(println (vector? "ab")) ; false
```
