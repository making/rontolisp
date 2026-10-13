# type

`(type x)`

The `:type` entry of the value's metadata when it is logically true (any value: a keyword, a
string ...), else the host class where the oracle answers one, else what
[class](class.md) answers -- the oracle's `(or (get (meta x) :type) (class x))`.
A function, keyword, string, symbol, boolean, map or set answers its host class as a
`(:C%CLASS name)` designator, a pattern its host `java.util.regex.Pattern` class where a
host is (its designator where none is), nil answers nil: a schema registry lookup falls
through to `(type x)` on a miss, where the oracle's class misses every entry but
`Pattern`'s, so an unregistered value refuses with `:malli.core/invalid-schema` like the
oracle instead of looping through (or mistyping under) the colliding kind entry, while
`(m/schema #"a")` builds the regex schema. `class` keeps the kind
keyword, and a designator dispatches and `instance?`s as its kind. As a `defmulti` dispatch
function a nil answers nil, so a `nil` method takes it. As a value a one-argument lambda.

Deviation: without a `:type` entry a function, keyword, string, symbol, boolean, map or set
answers the designator where the oracle answers its host class object; a pattern answers
the host class object itself where a host is, its designator on the wasm backends; any
other value answers `class`'s kind keyword. Two functions answer `=` designators (the oracle tells their
classes apart); a map names its size's class (`PersistentArrayMap`, past eight entries
`PersistentHashMap`) and a sorted collection its tree class, like the oracle's classes.

```clojure
(defrecord Circle [r])
(println (type (with-meta {:r 1} {:type :circle})) (= (type (->Circle 1)) (class (->Circle 2)))) ; :circle true
(println (type "s") (type nil)) ; (:C%CLASS java.lang.String) nil
(println (= (type #"a") java.util.regex.Pattern)) ; true
(defmulti area type)
(defmethod area :circle [x] (* 3 (:r x) (:r x)))
(defmethod area Circle [x] (* 3 (:r x) (:r x)))
(println (area (with-meta {:r 2} {:type :circle})) (area (->Circle 1))) ; 12 3
```
