# pop

`(pop place)`

Removes the first element from the list stored in `place`: it returns that element and stores the rest of the list (its `cdr`) back into `place`. `place` may be any location `setf` accepts. Calling `pop` on nil returns nil and leaves the place as nil.

```lisp
(let ((s (list 1 2 3))) (pop s)) ; => 1
```
