# in-ns

`(in-ns 'name)`

Switches the current namespace, answering `nil`. The namespace is flat: nothing is defined or
looked up per-namespace beyond the alias and refer wirings, so the switch is bookkeeping.

```clojure
(println (in-ns 'demo)) ; nil
```
