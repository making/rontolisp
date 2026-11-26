# butlast

`(butlast list &optional n)`

Returns a fresh copy of `list` with its last `n` conses removed (one by default); the original is not modified. A count at or past the length yields `nil`. For a dotted list the count is in CONSES, so the terminating atom is not one of them. See [`nbutlast`](nbutlast.md) for the destructive spelling.

```lisp
(butlast '(1 2 3 4)) ; => (1 2 3)
```

```lisp
(butlast '(1 2 3 4) 2) ; => (1 2)
```
