# real?

`(real? obj)`

Returns `#t` when `obj` is a real number: every non-complex number, `+inf.0` and `+nan.0` included. A complex number with a nonzero imaginary part is not real.

```scheme
(real? 1.5) ; => #t
(real? "1.5") ; => #f
```
