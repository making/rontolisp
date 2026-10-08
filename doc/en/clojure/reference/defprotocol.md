# defprotocol

`(defprotocol Name docstring? (method [target & args]+ docstring?) ...)`

Declares a protocol: a method-table global plus one dispatcher per method over the
target's tag (the multimethod shape without the hierarchy search). The name answers
its method table. A call dispatches on the target's exact tag, then on a class the
target extends or implements that the protocol was extended to (a throwable, `IRef`, ...:
[extend-protocol](extend-protocol.md)), then the `Object` row; a miss with no `Object` row
signals, like the oracle. A method declares one
parameter vector per arity, each taking the target first, and a call reaches the
implementation's arity of its argument count. A `defrecord`, `deftype` or `reify` body
implements another arity by naming the method again over another parameter vector, each
one an arity the protocol declares; [extend-protocol](extend-protocol.md),
[extend-type](extend-type.md) and [extend](extend.md) spell the arities as the clauses
of a `fn`. A count the implementation does not take signals an `ArityException` (the
oracle throws an `AbstractMethodError` for an arity an inline body leaves out).

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

(defprotocol Scaled (scale [s] [s k]))
(defrecord Square [side] Scaled (scale [_] (* side side)) (scale [this k] (* k (scale this))))
(extend-protocol Scaled String (scale ([s] (count s)) ([s k] (* k (count s)))))
(println (scale (->Square 3)) (scale (->Square 3) 2)) ; 9 18
(println (scale "abc") (scale "abc" 2))               ; 3 6
```
