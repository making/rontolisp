# seqable?

`(seqable? x)`

`clojure.core/seqable?`: `true` for what `seq` takes: `nil`, a string or a collection (list, lazy seq, vector, map, set, record). As a value a one-argument function.

```clojure
(println (seqable? nil) (seqable? "ab") (seqable? 1) (seqable? :a))  ; true true false false
```
