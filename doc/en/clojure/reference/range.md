# range

`(range)` / `(range end)` / `(range start end)` / `(range start end step)`

With an end, answers the strict list of the arithmetic progression from `start`
(default `0`) below `end` (above it for a negative step) by `step` (default `1`); a
zero step signals. Builds the whole list -- no laziness. Without an end, answers the
infinite lazy integers from `0`, like the oracle's: `take`, `nth`, `map`, `filter`,
`drop` and `reduce` walk it, `first` answers `0`, `empty?` is false. Like every lazy
seq here it realizes one element at a time (no chunking).

```clojure
(println (range 5))       ; (0 1 2 3 4)
(println (range 2 8))     ; (2 3 4 5 6 7)
(println (range 0 10 3))  ; (0 3 6 9)
(println (range 5 0 -2))  ; (5 3 1)
(println (take 3 (range))) ; (0 1 2)
```
