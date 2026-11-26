# linalg:slice

`(linalg:slice array specs)`

Basic numpy slicing -- `x[i0:j0:k0, i1:j1, ...]` -- spelled as a list with one spec per axis. A spec is either `nil` (leave the axis whole) or `(start end)` / `(start end step)`. A negative index counts from the end, `nil` in the `start` or `end` position means "from the beginning" / "to the end", a negative `step` walks the axis backwards, and a **missing trailing spec** leaves that axis whole. Every axis is kept, exactly as numpy's `x[:, 0:3]` keeps both; to take one slice and *drop* its axis, use [`linalg:row`](linalg-row.md). The result is a fresh array with the input's element width.

```lisp
(linalg:slice #2A((0 1 2) (3 4 5)) '(nil (0 2))) ; => #d((0.0 1.0) (3.0 4.0))
(linalg:slice #2A((0 1 2) (3 4 5)) '((1 2)))     ; => #d((3.0 4.0 5.0))
(linalg:slice #(0 1 2 3 4 5) '((nil nil 2)))     ; => #d(0.0 2.0 4.0)
(linalg:slice #(0 1 2 3 4 5) '((-2 nil)))        ; => #d(4.0 5.0)
```
