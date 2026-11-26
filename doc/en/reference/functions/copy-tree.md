# copy-tree

`(copy-tree tree)`

Returns a deep copy of a cons tree: every cons cell is fresh, while non-cons leaves (numbers, symbols, strings, ...) are shared with the original. Compare `copy-list`, which copies only the top-level spine.

```lisp
(copy-tree '(1 (2 3) . 4)) ; => (1 (2 3) . 4)
```

```lisp
(let* ((orig (list (list 1 2)))
       (copy (copy-tree orig)))
  (setf (car (car copy)) 99)
  orig) ; => ((1 2))
```
