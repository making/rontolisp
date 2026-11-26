# nconc

`(nconc &rest lists)`

Destructively concatenates its list arguments by setting the last cdr of each non-empty list to point at the following argument, then returns the first non-`nil` argument. No new cons cells are allocated, so the argument lists are modified in place. `(nconc)` returns `nil`, `(nconc x)` returns `x`, and `nil` arguments are skipped. The last argument may be any object (it is spliced onto the tail of the preceding list without being copied). It is also never traversed, so `(nconc x x)` — the usual way to build a circular list — links `x` onto itself and returns instead of chasing the cycle it just created.

```lisp
(nconc (list 1 2) (list 3 4) (list 5)) ; => (1 2 3 4 5)
```
