# remove-if

`(remove-if predicate sequence &key key)`

Returns a new sequence containing the elements of `sequence` that do **not** satisfy `predicate` (the satisfying elements are removed). The sequence may be a list or a string; a string yields a new string. The original sequence is not modified; use `delete-if` for the destructive version (lists only). With `:key`, the predicate sees the keyed value while the kept elements are the originals.

```lisp
(remove-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(remove-if #'digit-char-p "a1b2") ; => "ab"
```

```lisp
(remove-if #'evenp '((1 a) (2 b) (3 c)) :key #'car) ; => ((1 A) (3 C))
```
