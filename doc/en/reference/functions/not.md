# not

`(not object)`

Logical negation: returns `t` if `object` is `nil` (false), otherwise `nil`. Because `nil` is the only false value, any non-nil argument yields `nil`. It is identical in behavior to `null`; choose `not` for boolean contexts and `null` for empty-list tests. Works in all three backends.

```lisp
(not nil) ; => T
```

```lisp
(not 42) ; => NIL
```
