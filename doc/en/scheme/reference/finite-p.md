# finite?

`(finite? z)`

Returns `#t` if `z` is neither infinite nor a NaN. Every exact number is finite.

```scheme
(finite? 1.5) ; => #t
(finite? (/ 1.0 0.0)) ; => #f
(finite? -inf.0) ; => #f
```
