# pushnew

`(pushnew item place &key test key)`

Prepends `item` to the list stored in `place` only when it is not already a member, and stores the result back. Membership is decided as [`adjoin`](../functions/adjoin.md) decides it: `eql`, or the given `:test`, between the item and each element, both taken through `:key` when one is given. Returns the (possibly unchanged) list. Like `push`, the place may be evaluated more than once.

```lisp
(setq ns (list 2 3))
(pushnew 1 ns) ; => (1 2 3)
(pushnew 2 ns) ; => (1 2 3)
ns             ; => (1 2 3)
```

```lisp
(setq pairs (list (list "a" 1)))
(pushnew (list "a" 2) pairs :key #'first :test #'string=) ; => (("a" 1))
```
