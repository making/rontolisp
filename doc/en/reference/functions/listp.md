# listp

`(listp object)`

Returns `t` if `object` is a list -- that is, either a cons cell or the empty list `nil` -- otherwise `nil`. Because `nil` counts as a list, `(listp nil)` is `t`, which is where `listp` differs from `consp`. Works in all three backends.

```lisp
(listp '(1 2)) ; => t
```

```lisp
(listp nil) ; => t
```
