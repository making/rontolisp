# /=

`(/= number...)`

Returns `t` when every argument is numerically different from every other (pairwise, as in Common Lisp), `nil` otherwise. Each argument is evaluated once.

```lisp
(/= 1 2) ; => T
```

```lisp
(/= 1 2 1) ; => NIL
```
