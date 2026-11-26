# fn?

`(fn? x)`

Answers whether `x` is a function, `T`-or-false. A keyword, a set or a map is no
function here even where it can be called.

```clojure
(defn twice [x] (* 2 x))
(println (fn? twice) (fn? #(+ % 1)) (fn? :k) (fn? #{1}))
```

```
true true false false
```
