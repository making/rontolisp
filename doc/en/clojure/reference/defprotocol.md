# defprotocol

`(defprotocol Name docstring? (method [target & args] docstring?) ...)`

Declares a protocol: a method-table global plus one dispatcher per method over the
target's tag (the multimethod shape without the hierarchy search). The name answers
its method table. A call dispatches on the target's exact tag, then the `Object`
row; a miss with no `Object` row signals, like the oracle. Each method takes one
parameter vector -- several arities stay refused.

With `:extend-via-metadata true` a value also implements a method through its
metadata ([with-meta](with-meta.md)), keyed by the namespace-qualified method symbol
(`` `area `` or `'user/area`). The order is the oracle's: an implementation in a
`defrecord`/`deftype`/`reify` body, then the metadata, then the `extend-type` rows
and `Object`. `satisfies?` does not look at metadata, like the oracle.

```clojure
(defprotocol P (greet [x]))
(defrecord R [name] P (greet [_] name))
(extend-protocol P nil (greet [_] :nobody))
(println (greet (->R "Ada"))) ; Ada
(println (greet nil))         ; :nobody

(defprotocol Area :extend-via-metadata true (area [s]))
(println (area (with-meta {:w 2 :h 3} {`area (fn [s] (* (:w s) (:h s)))}))) ; 6
```
