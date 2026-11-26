# nreconc

`(nreconc list tail)`

The destructive version of `revappend`: it reverses `list` in place and attaches `tail`, reusing `list`'s cons cells (it expands to `(nconc (nreverse list) tail)`). Because the input list is rewired, pass a fresh list and use the return value rather than the original variable.

```lisp
(nreconc (list 1 2 3) '(4 5)) ; => (3 2 1 4 5)
```
