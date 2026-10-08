# reduce

`(reduce f coll)` / `(reduce f val coll)`

Folds `f` over the seq view of `coll`, left to right, one element at a time, so a lazy
input reduces whole. The 3-arity takes the Clojure argument order -- function, initial
value, collection; the 2-arity folds with no seed, answering `(f)` of an empty collection
and the lone member of a one-member one. A [`reduced`](reduced.md) answer stops the fold
and answers its value.

A record, deftype or `reify` whose type has its own row of
`clojure.core.protocols/CollReduce`, in its body or extended to it, reduces through that
row's `coll-reduce`, like the oracle; so do the verbs built on `reduce`: `into`,
`transduce`, the `cat` transducer, `run!`, `mapv` and `filterv` of one collection,
`group-by` and `frequencies` ([clojure.datafy](clojure-datafy.md)).

```clojure
(println (reduce + '(1 2 3)))  ; 6
(println (reduce + 0 [1 2 3])) ; 6
(println (reduce (fn [a x] (if (> a 3) (reduced a) (+ a x))) [1 2 3 4 5])) ; 6
```
