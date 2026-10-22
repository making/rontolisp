# pmap

`(pmap f coll...)`

Runs one future per element on the interpreter and the JVM -- each application on
its own thread, dereferenced in order with the oracle's lookahead (two plus the
processor count) -- so sleep-backed branches overlap while the printed seq stays
the sequential one's. A lazy input stays lazy and sequential, like `map`, and a
failure surfaces at the deref in order, wrapped in the oracle's
`ExecutionException` over the branch condition. On the WASM legs, which have no
threads, it is `map`: the same printed seq. As a value the same function.

```clojure
(println (pmap inc [1 2 3])) ; (2 3 4)
(println (pmap + [1 2] [10 20 30])) ; (11 22)
```

```clojure
(println (pmap inc [1 2 3])) ; (2 3 4)
(println (pmap + [1 2] [10 20 30])) ; (11 22)
```
