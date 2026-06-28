# delete

`(delete item list)`

The destructive counterpart of `remove`: returns `list` with every element `eql` to `item` spliced out, modifying the cons cells in place rather than copying. Because the head of the list may change, always use the return value rather than relying on the original variable.

```lisp
(delete 2 '(1 2 3 2)) ; => (1 3)
```
