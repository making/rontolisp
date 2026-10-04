# descendants

`(descendants tag)`
`(descendants h tag)`

Answers the set of all descendants of `tag`, transitive, in the hierarchy -- the global one, or
`h` in the two-argument form, nil when there are none. Descendants of a class are the
oracle's refusal, an `UnsupportedOperationException`.

```clojure
(derive :c :p)
(derive :d :p)
(println (count (descendants :p))) ; 2
```
