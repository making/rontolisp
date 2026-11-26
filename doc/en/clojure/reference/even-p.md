# even?

`(even? n)`

`true` for an even integer. As a value, a one-argument lambda answering the boolean, so
`filter` takes it bare.

```clojure
(println (even? 2)) ; true
(println (filter even? '(1 2 3 4))) ; (2 4)
```
