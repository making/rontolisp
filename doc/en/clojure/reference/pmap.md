# pmap

`(pmap f coll...)`

Is `map`: there is no thread pool on any backend, so the calls run in order on the
calling thread and the printed answer is the oracle's. As a value the same function
as `map`.

```clojure
(println (pmap inc [1 2 3])) ; (2 3 4)
(println (pmap + [1 2] [10 20 30])) ; (11 22)
```
