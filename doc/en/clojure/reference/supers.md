# supers

`(supers c)`

Answers every class [bases](bases.md) reaches from the class `c` -- its superclasses and
interfaces, transitively, `Object` included -- as a set of class values; nil when there are
none and for nil. Any other argument is the oracle's `ClassCastException`. As a value a
one-argument lambda. It answers on every backend, by the tables `bases` reads where no host
reflects, with `bases`' deviations.

The set's order is its own; the oracle's hashes `Class` objects by identity, so it changes
from run to run there.

```clojure
(println (sort (map #(.getName %) (supers Long)))) ; (java.io.Serializable java.lang.Comparable java.lang.Number java.lang.Object java.lang.constant.Constable java.lang.constant.ConstantDesc)
(defn f [x] x)
(println (contains? (supers (class f)) clojure.lang.IFn) (count (supers (class [])))) ; true 31
```
