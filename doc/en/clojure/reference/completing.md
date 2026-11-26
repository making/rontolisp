# completing

`(completing f)` / `(completing f cf)`

Answers a reducing function with `f`'s init and step arities and `cf` as the completion
(`identity` without one), so a two-argument `f` can finish a `transduce`. As a value one
or two arguments.

```clojure
(println (transduce (comp (take 2) (map inc)) (completing + str) [1 2 3])) ; 5
(println (transduce (map inc) (completing (fn [acc x] (+ acc x))) 0 [1 2])) ; 5
```
