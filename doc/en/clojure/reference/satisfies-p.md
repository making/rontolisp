# satisfies?

`(satisfies? Protocol x)`

Whether the protocol reaches `x`: the tag's row, or the `Object` row an extension
installed, like the oracle. The protocol is a literal name, like `defmethod`'s
multimethod.

```clojure
(defprotocol P (m [x]))
(extend-protocol P Object (m [_] :other))
(println (satisfies? P 1))   ; true
(println (satisfies? P nil)) ; true
```
