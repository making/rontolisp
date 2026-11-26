# find-protocol-impl

`(find-protocol-impl protocol x)`

The map of `x`'s implementation: its tag row, else the `Object` default's,
else `nil`, like the oracle's. A non-protocol answers `nil`, where the
oracle's throws `NullPointerException`. As a value two arguments; a computed
protocol reads the method table itself, which holds no `Object` default, so
the default shows only through a literal protocol name.

```clojure
(defprotocol Sip (sip [x]))
(defrecord Mug [size])
(extend-protocol Sip Mug (sip [x] :drunk))
(println (= (count (find-protocol-impl Sip (->Mug 1))) 1)) ; true
```
