# linalg:to-list

`(linalg:to-list array)`

Converts a linalg array back into a list: a vector becomes a flat list, and a matrix becomes a list of row lists. It is the inverse of [`linalg:from-list`](linalg-from-list.md), useful for handing array contents to list functions like `mapcar` or `reduce`.

```lisp
(linalg:to-list (linalg:from-list '((1 2) (3 4)))) ; => ((1.0 2.0) (3.0 4.0))
```
