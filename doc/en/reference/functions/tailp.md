# tailp

`(tailp object list)`

Whether `object` is one of the tails of `list`: true when it is `eq` to one of the conses of the spine, or `eql` to the atom that terminates it. `(tailp nil list)` is therefore true for a proper list, and `(tailp 'e '(a b . e))` is true for a dotted one.

```lisp
(let ((l (list 1 2 3))) (tailp (cddr l) l)) ; => T
```

```lisp
(tailp 'e '(a b . e)) ; => T
```
