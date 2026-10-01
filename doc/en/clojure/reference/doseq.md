# doseq

`(doseq [binding...] body...)`

Runs the body once per combination of the bindings, left to right, and answers `nil`.
Each pair binds a pattern over a collection's seq view (lists, vectors, strings, maps,
sets, `nil`); patterns destructure like `let`. The `:when`/`:while`/`:let` modifiers
trail their binding in order: `:when` skips the element, `:while` ends its level's loop
(an outer level's ends the whole form), `:let` binds sequentially. An empty vector runs
the body once; over `nil` it never runs.

```clojure
(println (doseq [x [1 2] y [3 4]] (print [x y]))) ; [1 3][1 4][2 3][2 4]nil
(println (doseq [x [1 2 3] :when (odd? x)] (print x))) ; 13nil
```
