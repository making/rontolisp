# shiftf

`(shiftf place... new-value)`

Shifts values left through its [`setf`](setf.md)-able places: each place receives the old value of the place to its right, the last place receives `new-value`, and the OLD value of the first place is returned. All places and the new value are evaluated once, left to right.

```lisp
(let ((a 1) (b 2))
  (list (shiftf a b 9) a b)) ; => (1 2 9)
```
