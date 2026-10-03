# extends?

`(extends? protocol type)`

`clojure.core/extends?`: `true` when `type` implements `protocol` inline (`defrecord`/`deftype`) or through `extend`, `extend-type` or `extend-protocol`. An `Object` extension counts for `Object` alone, like the oracle. Both are literal names, like `satisfies?`'s protocol, and `type` takes the names `extend-type` takes; numeric classes share one row (`Long` and `Double` alike). It has no value form.

```clojure
(defprotocol ExP (ex-m [x]))
(defrecord ExR [a] ExP (ex-m [x] 1))
(defrecord ExS [a])
(println (extends? ExP ExR) (extends? ExP ExS))  ; true false
```
