# underive

`(underive tag parent)`
`(underive h tag parent)`

Drops the `tag`-to-`parent` edge. The two-argument form rewrites the global hierarchy and
answers `nil`; the three-argument form answers the updated hierarchy value.

```clojure
(derive :c :p)
(underive :c :p)
(println (isa? :c :p)) ; false
```
