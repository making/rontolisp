# run!

`(run! proc coll)`

Calls `proc` on every member of `coll` for effect and answers `nil`; a lazy seq is
realized member by member. Also names a function value, as do `println`, `print`, `prn`
and `pr`.

```clojure
(run! println [1 2]) ; prints 1 and 2
(println (run! inc [1 2])) ; nil
```
