# nreverse

`(nreverse sequence)`

Reverses `sequence` destructively by rewiring each cons cell's `cdr`, then returns the new head. Because the original cells are reused and the head changes, you must use the return value -- the variable you passed in no longer points at the full reversed list. When you need to keep the original, use `reverse` instead.

```lisp
(nreverse (list 1 2 3)) ; => (3 2 1)
```
