# linalg:concatenate

`(linalg:concatenate arrays &key axis)`

Joins the arrays in the list `arrays` along an **existing** axis (numpy's `np.concatenate`, torch's `cat`). `:axis` defaults to 0 and a negative value counts from the end. Every input must have the same rank and the same extents on every axis but that one; the joined axis's extent is their sum. The result is a fresh array with the first input's element width. To join along a **new** axis instead, use [`linalg:stack`](linalg-stack.md).

```lisp
(linalg:concatenate (list #(1 2) #(3)))                   ; => #d(1.0 2.0 3.0)
(linalg:concatenate (list #2A((1 2)) #2A((3 4))))         ; => #d((1.0 2.0) (3.0 4.0))
(linalg:concatenate (list #2A((1 2)) #2A((3 4))) :axis 1) ; => #d((1.0 2.0 3.0 4.0))
```
