# extend-type

`(extend-type Type Protocol (method [target & args] body...) ...)`

Adds rows to protocols' tables for one type: the same rows `extend-protocol` stores,
grouped under protocol names. Method groups without a protocol name are refused --
there is no interface to check them against. Unlike inline `defrecord`/`deftype`
bodies, the methods do not see the type's fields as locals.

```clojure
(defprotocol P (m [x]))
(extend-type String P (m [s] :str))
(println (m "s")) ; :str
```
