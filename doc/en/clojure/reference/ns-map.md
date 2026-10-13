# ns-map

`(ns-map ns)`

Every mapping of the namespace `ns` (a symbol, a string or `*ns*`) -- its interns, its
refers, the referred `clojure.core` vars and its imports -- each name to its var, like the
oracle's. Only what the lowering knows maps, and anything computed refuses at run time. An
import maps to a symbol of its class (classes exist only on the host backends). Mappings past
a hundred pairs are built in pieces, so no method outgrows the backends. As a value one
argument.

```clojure
(println (get (ns-map 'user) 'map)) ; #'clojure.core/map
```
