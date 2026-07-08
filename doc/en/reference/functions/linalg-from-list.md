# linalg:from-list

`(linalg:from-list list)`

Converts a list into a linalg array: a flat list becomes a rank-1 vector, and a list of equal-length row lists becomes a rank-2 matrix. This is the usual way to write array literals in linalg code. The inverse conversion is [`linalg:to-list`](linalg-to-list.md).

```lisp
(linalg:from-list '(1 2 3))     ; => #d(1.0 2.0 3.0)
(linalg:from-list '((1 2) (3 4))) ; => #d((1.0 2.0) (3.0 4.0))
```
