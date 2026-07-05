# pushnew

`(pushnew item place &key test key)`

Prepends `item` to the list stored in `place` only when it is not already a member (compared with `eql`, or the given `:test`), and stores the result back. Returns the (possibly unchanged) list. Like `push`, the place may be evaluated more than once.

```lisp
(setq ns (list 2 3))
(pushnew 1 ns) ; => (1 2 3)
(pushnew 2 ns) ; => (1 2 3)
ns             ; => (1 2 3)
```
