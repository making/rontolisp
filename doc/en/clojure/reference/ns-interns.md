# ns-interns

`(ns-interns ns)`

Every intern of the namespace `ns` (a symbol, a string or `*ns*`), each name to its var,
like the oracle's. Only what the lowering knows maps: a `clojure.core` var the subset lacks
stays out (as with `resolve`), and anything computed refuses at run time. As a value one
argument.

```clojure
(defn- doc-eg-h [] 2)
(println (some? (get (ns-interns 'user) 'doc-eg-h))) ; true
```
