# array-element-type

`(array-element-type array)`

Returns the array's element type. Element types are not tracked (every array can hold any value, and [`make-array`](make-array.md) accepts but ignores `:element-type`), so the answer is always the symbol `t`. Provided for compatibility with portable code such as cl-utilities' `copy-array`.

```lisp
(array-element-type (make-array 3)) ; => T
```
