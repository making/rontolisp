# defonce

`(defonce name init?)`

`def` unless the name is already bound: the first evaluation wins, so a reload
keeps the root where `def` would reset it. An unbound var (`declare`, a value-less
`def`) is not bound, so `defonce` binds it.

```clojure
(defonce words ["a" "b"])
(defonce words ["c"])
(println words) ; [a b]
```
