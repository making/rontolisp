# defprotocol

`(defprotocol Name docstring? (method [target & args] docstring?) ...)`

Declares a protocol: a method-table global plus one dispatcher per method over the
target's tag (the multimethod shape without the hierarchy search). The name answers
its method table. A call dispatches on the target's exact tag, then the `Object`
row; a miss with no `Object` row signals, like the oracle. Each method takes one
parameter vector -- several arities stay refused, as does `:extend-via-metadata`.

```clojure
(defprotocol P (greet [x]))
(defrecord R [name] P (greet [_] name))
(extend-protocol P nil (greet [_] :nobody))
(println (greet (->R "Ada"))) ; Ada
(println (greet nil))         ; :nobody
```
