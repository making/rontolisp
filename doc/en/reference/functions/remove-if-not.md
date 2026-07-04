# remove-if-not

`(remove-if-not predicate sequence)`

Returns a new sequence keeping only the elements of `sequence` that satisfy `predicate` (those failing it are removed). The sequence may be a list or a string; a string yields a new string. It is the complement of `remove-if`. The original sequence is not modified.

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```

```lisp
(remove-if-not #'digit-char-p "a1b2") ; => "12"
```
