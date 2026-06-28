# push

`(push item place)`

Prepends `item` to the list stored in `place`, stores the resulting longer list back into `place`, and returns that new list. `place` may be any location `setf` accepts, not just a variable. It expands into `(setf place (cons item place))`.

```lisp
(let ((s (list 1 2))) (push 0 s) s) ; => (0 1 2)
```
