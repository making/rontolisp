# numberp

`(numberp object)`

Returns `t` if `object` is a number -- an integer, a float, a ratio, or a complex -- otherwise `nil`. Works in all three backends.

```lisp
(numberp 42) ; => T
```

```lisp
(numberp #c(1 2)) ; => T
```

```lisp
(numberp "42") ; => NIL
```
