# isa?

`(isa? child parent)`
`(isa? h child parent)`
`(isa? vec1 vec2)`

Answers whether `child` relates to `parent`: equality, element-wise vector derivation (each
component `isa?` the matching one), or ancestor membership through the hierarchy -- the global
one, or `h` in the three-argument form. Answers `true` or `false`; the multimethod dispatch
search is built on it.

A class spelling as `child` or `parent` is the keyword `class` answers for its values, as in a
`defmethod`: `String` is `:string`, a record its tag, a throwable or stream class its name. A
throwable or stream class keyword `isa?` each of its superclasses, and whatever they derive
from, like the oracle's Java inheritance (interfaces and `Object` aside).

```clojure
(derive :c :p)
(println (isa? :c :p)) ; true
(println (isa? :c :c)) ; true
(println (isa? :p :c)) ; false
(println (isa? (class "a") String)) ; true
(println (isa? (class (NumberFormatException. "x")) IllegalArgumentException)) ; true
```
