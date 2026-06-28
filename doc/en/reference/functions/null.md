# null

`(null object)`

Returns `t` if `object` is the empty list `nil`, otherwise `nil`. Since `nil` is the only false value, this also serves as the logical-false test. It is identical in behavior to `not`. Works in all three backends.

```lisp
(null nil) ; => t
```

```lisp
(null '(1 2)) ; => nil
```
