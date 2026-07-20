# cdr

`(cdr list)`

Returns the cdr of a cons cell -- the rest of a list after its first element. As a special case `(cdr nil)` is `nil` rather than an error. Use `rest` as a more readable synonym when working with lists.

```lisp
(cdr '(1 2 3)) ; => (2 3)
```

```lisp
(cdr nil) ; => NIL
```
