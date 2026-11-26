# identical?

`(identical? x y)`

`clojure.core/identical?`: `true` when `x` and `y` are the same object. Two keywords of one spelling are one object, like the oracle's interned keywords. Numbers, characters and symbols compare by value here, so `(identical? 1000 1000)` and `(identical? 'a 'a)` are `true` (the oracle: `false`). A Java object is identical only to itself, like the oracle's, even when its `equals` says otherwise (`=` asks `equals`). As a value a two-argument function.

```clojure
(println (identical? :a :a) (let [v [1]] (identical? v v)) (identical? [1] [1]))  ; true true false
```
