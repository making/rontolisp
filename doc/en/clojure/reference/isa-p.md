# isa?

`(isa? child parent)`
`(isa? h child parent)`
`(isa? vec1 vec2)`

Answers whether `child` relates to `parent`: equality, element-wise vector derivation (each
component `isa?` the matching one), or ancestor membership through the hierarchy -- the global
one, or `h` in the three-argument form. Answers `true` or `false`; the multimethod dispatch
search is built on it.

```clojure
(derive :c :p)
(println (isa? :c :p)) ; true
(println (isa? :c :c)) ; true
(println (isa? :p :c)) ; false
```
