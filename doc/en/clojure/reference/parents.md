# parents

`(parents tag)`
`(parents h tag)`

Answers the set of the immediate parents of `tag` in the hierarchy -- the global one, or `h` in
the two-argument form. An unrelated tag answers the empty set.

```clojure
(derive :c :p)
(println (parents :c)) ; #{:p}
```
