# in-ns

`(in-ns 'name)`

Switches the current namespace, creating it, and answers `nil`: the definitions below it
belong to that namespace and names resolve there. Unlike `ns` it wires nothing and loads
nothing, and the core stays visible (the oracle's fresh `in-ns` namespace sees none).

```clojure
(println (in-ns 'demo)) ; nil
```
