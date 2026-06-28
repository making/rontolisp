# numberp

`(numberp object)`

Returns `t` if `object` is a number -- an integer, a float, or a ratio -- otherwise `nil`. Works in all three backends.

```lisp
(numberp 42) ; => t
```

```lisp
(numberp "42") ; => nil
```
