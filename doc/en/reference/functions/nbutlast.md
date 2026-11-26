# nbutlast

`(nbutlast list &optional n)`

The DESTRUCTIVE [`butlast`](butlast.md): the last `n` conses (one by default) are cut off `list` itself with an `rplacd`, and the same list is returned. When nothing would be left the answer is nil and the argument is untouched, since there is no cell to cut. The argument must not be used afterwards except through the returned value.

```lisp
(nbutlast (list 1 2 3 4) 2) ; => (1 2)
```

```lisp
(nbutlast (list 1 2 3) 5) ; => NIL
```
