# substitute

`(substitute new old sequence &key test key)`

Returns a new sequence in which every element matching `old` is replaced by `new`; all other elements are kept unchanged. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison (the replacement value is `new` itself, unkeyed). The sequence may be a list or a string; a string yields a new string (with `new` a character). The original sequence is not modified; use `nsubstitute` for the destructive version (lists only).

```lisp
(substitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(substitute #\o #\a "banana") ; => "bonono"
```

```lisp
(substitute "X" "b" '("a" "b" "c") :test #'string=) ; => ("a" "X" "c")
```
