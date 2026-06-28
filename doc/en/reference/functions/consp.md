# consp

`(consp object)`

Returns `t` if `object` is a cons cell, otherwise `nil`. The empty list `nil` is not a cons cell, so `(consp nil)` is `nil` -- this is where `consp` differs from `listp`. It is the exact complement of `atom`. Works in all three backends.

```lisp
(consp '(1 2)) ; => t
```

```lisp
(consp nil) ; => nil
```
