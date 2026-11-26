# endp

`(endp list)`

The end-of-list test: returns `t` when `list` is `nil` (the empty list) and `nil` when it is a cons cell. It is the canonical way to detect the end while cdr-ing down a list. Anything else signals a `type-error` (expected type `LIST`), so an improper list's tail is caught.

```lisp
(endp '(1)) ; => NIL
```

```lisp
(endp nil) ; => T
```

```lisp
(handler-case (endp 5) (type-error (e) (princ-to-string e))) ; => "ENDP: The value 5 is not of type LIST"
```
