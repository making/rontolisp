# remprop

`(remprop symbol indicator)`

Removes the `indicator` property from the symbol's property list, returning true when it was there and `nil` when it was not. The partner of [`get`](get.md) / `(setf (get ...))` and [`symbol-plist`](symbol-plist.md): all four read the one program-global, name-keyed store (symbols have no identity cells to hang plists on). Common Lisp only promises a generalized boolean here; this returns `t`, where some implementations return the plist tail.

```lisp
(setf (get 'my-node 'color) :red)
(setf (get 'my-node 'size) 3)
(list (remprop 'my-node 'color) (symbol-plist 'my-node) (remprop 'my-node 'color))
; => (T (SIZE 3) NIL)
```
