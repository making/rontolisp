# reify

`(reify Protocol (method [target & args] body...) ...)`

Answers one fresh dispatch value per evaluation with a row per method in each
protocol's table: a single-shot map plus methods (never `proxy`, which stays the
`java:` surface). Each instance dispatches through its own tag, so two instances
are never `=` to each other, like the oracle; `=` is identity otherwise. Method
groups stand under protocol names, like `extend-type`. A method named again over
another parameter vector implements another of its arities.

```clojure
(defprotocol P (m [x]))
(def a (reify P (m [_] :a)))
(def b (reify P (m [_] :b)))
(println (m a))       ; :a
(println (m b))       ; :b
(println (= a a))     ; true
(println (= a b))     ; false
(println (satisfies? P a)) ; true
```

A `reify` of `clojure.core.protocols/CollReduce` is a collection `reduce`, `into` and
`transduce` reduce through its `coll-reduce`
([clojure.core.reducers](clojure-core-reducers.md) builds its reducers that way):

```clojure
(require '[clojure.core.protocols :as p])
(def three (reify p/CollReduce
             (coll-reduce [this f] (p/coll-reduce this f (f)))
             (coll-reduce [_ f init] (reduce f init [1 2 3]))))
(reduce + three) ; => 6
(into [] (map inc) three) ; => [2 3 4]
```
