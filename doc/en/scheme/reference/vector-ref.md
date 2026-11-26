# vector-ref

`(vector-ref vector k)`

Returns the element at zero-based index `k` of `vector`. An index out of range ends the program with an error.

```scheme
(vector-ref #(a b c) 1) ; => b
(vector-ref #(a b c) 0) ; => a
```
