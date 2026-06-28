# rest

`(rest list)`

Returns the list with its first element removed -- everything after the head. It is an exact synonym for `cdr`, including the `(rest nil)` is `nil` behavior, and pairs with `first` for readable list traversal.

```lisp
(rest '(10 20 30)) ; => (20 30)
```
