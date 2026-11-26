# identity

`(identity x)`

Answers `x` itself. As a value the one-argument lambda, so bare `identity`
maps.

```clojure
(println (identity 1)) ; 1
(println (map identity [1 2])) ; (1 2)
```
