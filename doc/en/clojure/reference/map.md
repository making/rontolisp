# map

`(map f coll...)` / `(map f)`

Answers `f` over each element (each row of elements) of the seq views, stopping at the
shortest input, like the oracle; lists pass through untouched, every other collection
coerces first, so vectors, strings, maps (entry vectors) and sets all work. The empty
result is `nil`. When any input is lazy the answer is a lazy seq, realized element by
element. As a value a rest-args lambda, so `map` takes a bare `inc`-style name.

`(map f)` is its [transducer](transducers.md), as a value too.

```clojure
(println (map inc '(1 2)))      ; (2 3)
(println (map #(inc %) [1 2 3])) ; (2 3 4)
(println (map :k [{:k 1} {:k 2}])) ; (1 2)
(println (map + [1 2 3] [10 20])) ; (11 22)
(println (take 3 (map inc (iterate inc 0)))) ; (1 2 3)
(println (into [] (map inc) [1 2])) ; [2 3]
```
