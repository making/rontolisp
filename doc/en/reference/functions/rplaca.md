# rplaca

`(rplaca cons object)`

Destructively replaces the car of `cons` with `object`, modifying the cons cell in place. Returns the modified cons cell itself (not the new car), so any other reference to the same cell sees the change. This is the primitive that `setf` of `car` expands to.

```lisp
(let ((c (cons 1 2))) (rplaca c 99) c) ; => (99 . 2)
```
