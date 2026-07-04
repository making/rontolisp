# remove-duplicates

`(remove-duplicates sequence &key test key)`

Returns a new sequence with duplicate elements removed, keeping the last occurrence of each (so the order of the surviving elements follows their last appearance). The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. The sequence may be a list or a string; a string yields a new string. The original sequence is not modified.

```lisp
(remove-duplicates '(1 2 1 3)) ; => (2 1 3)
```

```lisp
(remove-duplicates "banana") ; => "bna"
```

```lisp
(remove-duplicates '("a" "b" "a" "c") :test #'string=) ; => ("b" "a" "c")
```
