# dotimes

`(dotimes [name n] body...)`

Binds `name` to `0` below `n` in turn, runs the body for effect, and answers `nil`. The
count runs through `truncate` first, like the oracle's `intCast`: `2.5` counts `0 1`,
and a non-number signals.

```clojure
(println (dotimes [i 3] (print i))) ; 012nil
(println (dotimes [i 0] :zero)) ; nil
```
