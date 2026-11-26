# mapv

`(mapv f coll...)`

Maps `f` over one or more collections (stopping at the shortest, like `map`) and
answers a vector -- never a lazy wrapper: lazy inputs realize fully. As a value a
rest lambda over the function and the collections.

```clojure
(println (mapv inc [1 2 3])) ; [2 3 4]
(println (mapv + [1 2] [10 20])) ; [11 22]
```
