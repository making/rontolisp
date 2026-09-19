# infinite?

`(infinite? z)`

Returns `#t` if `z` is positive or negative infinity. No exact number is infinite.

```scheme
(infinite? (/ -1.0 0.0)) ; => #t
(infinite? 3) ; => #f
(infinite? +inf.0) ; => #t
```
