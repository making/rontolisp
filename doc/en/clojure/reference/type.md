# type

`(type x)`

The `:type` entry of the value's metadata when it is logically true (any value: a keyword, a
string ...), else what [class](class.md) answers -- the oracle's
`(or (get (meta x) :type) (class x))`. A schema registry lookup falls through to `(type x)` on a
miss, where a class value misses every entry but `Pattern`'s, like the oracle's, so an
unregistered value refuses with `:malli.core/invalid-schema` while `(m/schema #"a")` builds the
regex schema. As a `defmulti` dispatch function a nil answers nil, so a `nil` method takes it. As a
value a one-argument lambda.

Deviation: `class`'s (two functions answer the same class, `clojure.lang.AFunction`, where the
oracle tells their classes apart).

```clojure
(defrecord Circle [r])
(println (type (with-meta {:r 1} {:type :circle})) (= (type (->Circle 1)) (class (->Circle 2)))) ; :circle true
(println (type "s") (type nil)) ; java.lang.String nil
(println (= (type #"a") java.util.regex.Pattern)) ; true
(defmulti area type)
(defmethod area :circle [x] (* 3 (:r x) (:r x)))
(defmethod area Circle [x] (* 3 (:r x) (:r x)))
(println (area (with-meta {:r 2} {:type :circle})) (area (->Circle 1))) ; 12 3
```
