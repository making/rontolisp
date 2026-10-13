# find-protocol-method

`(find-protocol-method protocol method x)`

`x`'s function for the method key: its tag row's, else the `Object` default's,
else `nil`, like the oracle's. A non-protocol answers `nil`, where the
oracle's throws `NullPointerException`. As a value three arguments; a computed
protocol reads the method table itself, which holds no `Object` default, so
the default shows only through a literal protocol name.

```clojure
(defprotocol Sip (sip [x]))
(defrecord Mug [size])
(extend-protocol Sip Mug (sip [x] :drunk))
(println ((find-protocol-method Sip :sip (->Mug 1)) (->Mug 1))) ; :drunk
```
