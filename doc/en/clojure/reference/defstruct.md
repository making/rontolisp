# defstruct

`(defstruct name key...)`

Holds the key vector behind the name, so `struct`/`struct-map` build maps with
exactly those keys. Keys are keywords, like the oracle (whose struct maps print
the same way single-entry maps do here).

```clojure
(defstruct planet :name :moons)
(println (:name (struct planet "Venus" 0))) ; Venus
```
