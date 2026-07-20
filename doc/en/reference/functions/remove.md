# remove

`(remove item sequence &key test key)`

Returns a new sequence containing the elements of `sequence` with every element matching `item` omitted. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison (the kept elements are the original ones). The sequence may be a list or a string; a string yields a new string. The original sequence is not modified; use `delete` for the destructive version (lists only).

```lisp
(remove 2 '(1 2 3 2)) ; => (1 3)
```

```lisp
(remove #\l "hello") ; => "heo"
```

```lisp
(remove 1 '((1 a) (2 b) (1 c)) :key #'car) ; => ((2 B))
```
