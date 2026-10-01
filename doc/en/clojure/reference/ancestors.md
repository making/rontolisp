# ancestors

`(ancestors tag)`
`(ancestors h tag)`

Answers the set of all ancestors of `tag`, transitive, in the hierarchy -- the global one, or
`h` in the two-argument form.

```clojure
(derive :c :p)
(derive :p :q)
(println (ancestors :c)) ; #{:p :q}
```
