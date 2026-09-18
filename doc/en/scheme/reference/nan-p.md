# nan?

`(nan? z)`

Returns `#t` if `z` is a NaN. No exact number is a NaN. `+nan.0` cannot be written in source; compute it, e.g. `(/ 0.0 0.0)`.

```scheme
(nan? (/ 0.0 0.0)) ; => #t
(nan? 1) ; => #f
```
