# iterator-seq

`(iterator-seq iter)`

Answers the seq of the members a `java.util.Iterator` steps, `nil` when it has none, realized
one member at a time through `hasNext` and `next` (the oracle realizes 32 at a time). The
iterator may be a `reify` or `deftype` implementing `java.util.Iterator`
([collection interfaces](reify.md#collection-interfaces)), the `.iterator` of a core
collection or an `Iterable` type, or a host iterator (interpreter and JVM).

```clojure
(println (iterator-seq (.iterator [1 2 3]))) ; (1 2 3)
(deftype Countdown [^:unsynchronized-mutable n]
  java.util.Iterator
  (hasNext [_] (pos? n))
  (next [_] (let [v n] (set! n (dec n)) v)))
(println (iterator-seq (Countdown. 3))) ; (3 2 1)
(println (iterator-seq (.iterator []))) ; nil
```
