# make-hierarchy

`(make-hierarchy)`

Answers an empty hierarchy value: a map of `:parents`/`:ancestors`/`:descendants` tables. Feed
it to `derive`/`underive` three-argument forms and `isa?`/`parents`/`ancestors`/`descendants`
extra-argument forms, or to `defmulti`'s `:hierarchy`.

```clojure
(def h (make-hierarchy))
(println (isa? h :c :p)) ; false
(println (isa? (derive h :c :p) :c :p)) ; true
```
