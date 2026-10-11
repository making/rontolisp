# type

`(type x)`

The `:type` entry of the value's metadata when it is logically true (any value: a keyword, a
string ...), else what [class](class.md) answers -- the oracle's
`(or (get (meta x) :type) (class x))`. As a `defmulti` dispatch function a nil answers nil, so a
`nil` method takes it. As a value a one-argument lambda.

Deviation: without a `:type` entry it answers `class`'s kind keyword where the oracle answers a
host class.

```clojure
(defrecord Circle [r])
(println (type (with-meta {:r 1} {:type :circle})) (= (type (->Circle 1)) (class (->Circle 2)))) ; :circle true
(defmulti area type)
(defmethod area :circle [x] (* 3 (:r x) (:r x)))
(defmethod area Circle [x] (* 3 (:r x) (:r x)))
(println (area (with-meta {:r 2} {:type :circle})) (area (->Circle 1))) ; 12 3
```
