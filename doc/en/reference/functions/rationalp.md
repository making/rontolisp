# rationalp

`(rationalp object)`

Returns `t` if `object` is a rational number -- an integer or a ratio -- otherwise `nil`. Floats are not rational, so `(rationalp 3.14)` is `nil`. Works in all three backends.

```lisp
(rationalp 1/2) ; => T
```

```lisp
(rationalp 3.14) ; => NIL
```
