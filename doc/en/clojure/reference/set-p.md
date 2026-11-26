# set?

`(set? x)`

`clojure.core/set?`: `true` for a set, a sorted one too. As a value a one-argument function.

```clojure
(println (set? #{1}) (set? {}) (set? [1]))  ; true false false
```
