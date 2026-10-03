# sorted?

`(sorted? x)`

`clojure.core/sorted?`: `false` for every value: there are no sorted collections yet, so no value here is one. The argument is still evaluated. As a value a one-argument function.

```clojure
(println (sorted? {:a 1}) (sorted? [1 2]))  ; false false
```
