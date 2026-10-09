# Iteration

Side-effecting loops lower to nested loops stepping through the seq view one element at
a time (a lazy input included). `for` answers a strict list over strict collections and a
lazy seq from its first lazy collection on; there is no chunking, and the empty strict
`for` is `nil`. `dorun`/`doall` realize a lazy seq to its end; `run!` calls a function on
every member for effect. `iteration` builds a seqable, reducible value over repeated
calls of a step function.

| Name | Example | Result |
|---|---|---|
| `doseq` | `(println (doseq [x [1 2]] (print x)))` | `12nil` |
| `dotimes` | `(println (dotimes [i 3] (print i)))` | `012nil` |
| `for` | `(println (for [x [1 2 3] :when (odd? x)] (* x 10)))` | `(10 30)` |
| `dorun` | `(println (dorun [1 2 3]))` | `nil` |
| `doall` | `(println (doall [1 2 3]))` | `[1 2 3]` |
| `run!` | `(println (run! inc [1 2]))` | `nil` |
| `iteration` | `(println (vec (iteration (fn [k] (when (< k 3) k)) :initk 0 :kf inc)))` | `[0 1 2]` |
