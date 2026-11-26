# nan?

`(nan? z)`

Returns `#t` if `z` is a NaN. No exact number is a NaN.

```scheme
(nan? (/ 0.0 0.0)) ; => #t
(nan? 1) ; => #f
(nan? +nan.0) ; => #t
```
