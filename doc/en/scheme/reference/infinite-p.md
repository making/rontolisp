# infinite?

`(infinite? z)`

Returns `#t` if `z` is positive or negative infinity. No exact number is infinite. `+inf.0` cannot be written in source; compute it, e.g. `(/ 1.0 0.0)`.

```scheme
(infinite? (/ -1.0 0.0)) ; => #t
(infinite? 3) ; => #f
```
