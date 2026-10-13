# ns-unmap

`(ns-unmap ns sym)`

The mapping of `sym` in the namespace `ns` (an intern, a refer or a referred core var)
forgotten where the program lowers, answering `nil`, like the oracle's. Anything computed
refuses at run time. As a value two arguments.

```clojure
(refer 'clojure.string :only '[join])
(ns-unmap 'user 'join)
(println (get (ns-refers 'user) 'join)) ; nil
```
