# map

`(map f coll)`

Answers the list of `f` over each element of `coll`'s seq view; lists pass through
untouched, every other collection coerces first, so vectors, strings, maps (entry
vectors) and sets all work. The empty result is `nil`. As a value a lambda, so `map`
takes a bare `inc`-style name.

```clojure
(println (map inc '(1 2)))      ; (2 3)
(println (map #(inc %) [1 2 3])) ; (2 3 4)
(println (map :k [{:k 1} {:k 2}])) ; (1 2)
```
