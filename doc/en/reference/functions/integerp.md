# integerp

`(integerp object)`

Returns `t` if `object` is an integer, otherwise `nil`. Floats and ratios are not integers, so `(integerp 3.0)` and `(integerp 1/2)` are both `nil`. Works in all three backends.

```lisp
(integerp 42) ; => t
```

```lisp
(integerp 3.0) ; => nil
```
