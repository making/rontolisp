# remove

`(remove item sequence)`

Returns a new sequence containing the elements of `sequence` with every element `eql` to `item` omitted. The sequence may be a list or a string; a string yields a new string. The original sequence is not modified; use `delete` for the destructive version (lists only). Comparison is by `eql` only.

```lisp
(remove 2 '(1 2 3 2)) ; => (1 3)
```

```lisp
(remove #\l "hello") ; => "heo"
```
