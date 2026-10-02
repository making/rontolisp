# imag-part

`(imag-part z)`

Returns the imaginary part of the complex `z`. The imaginary part of a real `z` is `(* 0 z)`, so it carries `z`'s exactness: `0` for an exact `z`, `0.0` for a flonum.

```scheme
(imag-part #C(1 2)) ; => 2
(imag-part 5) ; => 0
```
