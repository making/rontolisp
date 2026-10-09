# extend

`(extend Type Protocol {method fn ...} ...)`

Adds rows to each protocol's table from its map of method functions: the same
rows `extend-protocol` stores, for use when the implementations already exist as
values. A map literal's rows are stored as written; a map the program computes
(`(assoc clojure.java.io/default-streams-impl ...)`, a var holding one) is stored entry
by entry when the form runs, an entry naming no method of the protocol left out, like
the oracle. `Object` as the type installs the miss default, like `extend-protocol`.

```clojure
(defprotocol P (m [x]))
(extend String P {:m (fn [s] :str)})
(println (m "s")) ; :str

(defprotocol Shape (area [s]) (label [s]))
(def shape-defaults {:label (fn [_] "shape")})
(defrecord Square [n])
(extend Square Shape (assoc shape-defaults :area (fn [s] (* (:n s) (:n s)))))
(println (area (->Square 3)) (label (->Square 3))) ; 9 shape
```
