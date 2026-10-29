# extends?

`(extends? protocol type)`

`clojure.core/extends?`: `true` when `type` implements `protocol` inline (`defrecord`/`deftype`) or through `extend`, `extend-type` or `extend-protocol`. An `Object` extension counts for `Object` alone, like the oracle. Two names read the tables while the program lowers, and `type` takes the names `extend-type` takes; a computed protocol or type (a form such as `(class x)`, or a local) reads the run-time protocol and type values, and so does `extends?` as a value (`(def e? extends?)` applied, or higher-order).

```clojure
(defprotocol ExP (ex-m [x]))
(defrecord ExR [a] ExP (ex-m [x] 1))
(defrecord ExS [a])
(println (extends? ExP ExR) (extends? ExP ExS))  ; true false
```
