# rplaca

`(rplaca cons object)`

Destructively replaces the car of `cons` with `object`, modifying the cons cell in place. Returns the modified cons cell itself (not the new car), so any other reference to the same cell sees the change. This is the primitive that `setf` of `car` expands to. Anything but a cons, `nil` included, signals a `type-error`.

```lisp
(let ((c (cons 1 2))) (rplaca c 99) c) ; => (99 . 2)
```

```lisp
(handler-case (rplaca nil 1) (type-error (e) (princ-to-string e))) ; => "RPLACA: The value NIL is not of type CONS"
```
