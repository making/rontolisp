# real?

`(real? obj)`

Returns `#t` when `obj` is a real number, which, without complex numbers, is every number, `+inf.0` and `+nan.0` included.

```scheme
(real? 1.5) ; => #t
(real? "1.5") ; => #f
```
