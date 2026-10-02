# Iteration

Side-effecting loops lower to nested loops stepping through the seq view one element at
a time (a lazy input included), and `for` to the same nesting accumulating in reverse into
a strict list. `for` answers no lazy seq and there is no chunking; the empty `for` is
`nil`.

| Name | Example | Result |
|---|---|---|
| `doseq` | `(println (doseq [x [1 2]] (print x)))` | `12nil` |
| `dotimes` | `(println (dotimes [i 3] (print i)))` | `012nil` |
| `for` | `(println (for [x [1 2 3] :when (odd? x)] (* x 10)))` | `(10 30)` |
| `dorun` | `(println (dorun [1 2 3]))` | `nil` |
| `doall` | `(println (doall [1 2 3]))` | `[1 2 3]` |
