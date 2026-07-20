# hash-table-p

`(hash-table-p object)`

Returns `t` if `object` is a hash table (one made by `make-hash-table`), and `nil` for anything else. A hash table is not a cons, so `consp` returns nil on it.

```lisp
(hash-table-p (make-hash-table)) ; => T
```
