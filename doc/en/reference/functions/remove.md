# remove

`(remove item list)`

Returns a new list containing the elements of `list` with every element `eql` to `item` omitted. The original list is not modified; use `delete` for the destructive version. Comparison is by `eql` only.

```lisp
(remove 2 '(1 2 3 2)) ; => (1 3)
```
