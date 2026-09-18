# finite?

`(finite? z)`

Returns `#t` if `z` is neither infinite nor a NaN. Every exact number is finite. The infinities can be computed (`(/ 1.0 0.0)`) but not written as `+inf.0` in source.

```scheme
(finite? 1.5) ; => #t
(finite? (/ 1.0 0.0)) ; => #f
```
