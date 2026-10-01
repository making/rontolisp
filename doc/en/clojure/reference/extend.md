# extend

`(extend Type Protocol {method fn ...})`

Adds rows to a protocol's table from a map literal of method functions: the same
rows `extend-protocol` stores, for use when the implementations already exist as
values. Anything but a literal map is refused. `Object` as the type installs the
miss default, like `extend-protocol`.

```clojure
(defprotocol P (m [x]))
(extend String P {:m (fn [s] :str)})
(println (m "s")) ; :str
```
