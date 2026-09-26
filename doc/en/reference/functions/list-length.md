# list-length

`(list-length list)`

The number of elements of a proper list, or nil when the list is CIRCULAR -- which is the difference from [`length`](length.md), and the reason the walk is a tortoise/hare pair. A dotted list or a non-list argument signals a `type-error`.

```lisp
(list-length '(a b c)) ; => 3
```

```lisp
(list-length nil) ; => 0
```

```lisp
(handler-case (list-length '(1 . 2)) (type-error (e) (princ-to-string e))) ; => "LIST-LENGTH: The value 2 is not of type LIST"
```
