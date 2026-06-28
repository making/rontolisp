# rplacd

`(rplacd cons object)`

Destructively replaces the cdr of `cons` with `object`, modifying the cons cell in place. Returns the modified cons cell itself, so the original reference now sees the new tail. This is the primitive that `setf` of `cdr` expands to, and the building block for splicing operations like `nconc`.

```lisp
(let ((c (cons 1 2))) (rplacd c 99) c) ; => (1 . 99)
```
