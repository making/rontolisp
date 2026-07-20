# endp

`(endp list)`

The end-of-list test: returns `t` when `list` is `nil` (the empty list) and `nil` when it is a cons cell. It is the canonical way to detect the end while cdr-ing down a list. In rontolisp it behaves as a synonym for `null` -- the strict improper-list type check of standard Common Lisp is relaxed.

```lisp
(endp '(1)) ; => NIL
```

```lisp
(endp nil) ; => T
```
