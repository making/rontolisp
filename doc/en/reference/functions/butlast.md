# butlast

`(butlast list)`

Returns a fresh copy of `list` with its last element removed; the original is not modified. An empty or single-element list yields `nil`. Unlike full Common Lisp, rontolisp's `butlast` takes only a list -- the optional count argument is not supported.

```lisp
(butlast '(1 2 3 4)) ; => (1 2 3)
```
