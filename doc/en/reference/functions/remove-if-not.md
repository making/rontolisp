# remove-if-not

`(remove-if-not predicate sequence &key key)`

Returns a new sequence keeping only the elements of `sequence` that satisfy `predicate` (those failing it are removed). The sequence may be a list or a string; a string yields a new string. It is the complement of `remove-if`. The original sequence is not modified. With `:key`, the predicate sees the keyed value while the kept elements are the originals.

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```

```lisp
(remove-if-not #'digit-char-p "a1b2") ; => "12"
```

```lisp
(remove-if-not #'evenp '((1 a) (2 b) (3 c)) :key #'car) ; => ((2 B))
```
