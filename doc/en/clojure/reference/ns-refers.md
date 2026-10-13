# ns-refers

`(ns-refers ns)`

The vars referred into the namespace `ns` (a symbol, a string or `*ns*`), each name to its
var, like the oracle's. Only what the lowering knows maps, and anything computed refuses at
run time. As a value one argument.

```clojure
(refer 'clojure.string :only '[join])
(println (some? (get (ns-refers 'user) 'join))) ; true
```
