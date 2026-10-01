# range

`(range end)` / `(range start end)` / `(range start end step)`

Answers the strict list of the arithmetic progression from `start` (default `0`) below
`end` (above it for a negative step) by `step` (default `1`); a zero step signals. Builds
the whole list -- no laziness.

Deviation: the end-less `(range)` is refused by name (`infinite range is not supported:
range needs an end`): an infinite seq cannot be spelled strictly -- spell it with
`iterate` instead.

```clojure
(println (range 5))       ; (0 1 2 3 4)
(println (range 2 8))     ; (2 3 4 5 6 7)
(println (range 0 10 3))  ; (0 3 6 9)
(println (range 5 0 -2))  ; (5 3 1)
```
