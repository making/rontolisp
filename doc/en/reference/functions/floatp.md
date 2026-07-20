# floatp

`(floatp object)`

Returns `t` if `object` is a floating-point number, otherwise `nil`. Integers and ratios are not floats, so `(floatp 3)` and `(floatp 1/2)` are both `nil`. Works in all three backends.

```lisp
(floatp 3.14) ; => T
```

```lisp
(floatp 3) ; => NIL
```
