# remove-if-not

`(remove-if-not predicate sequence &key key start end count from-end)`

Returns a new sequence keeping only the elements of `sequence` that satisfy `predicate` (those failing it are removed). The sequence may be a list or a string; a string yields a new string. It is the complement of `remove-if`. The original sequence is not modified. With `:key`, the predicate sees the keyed value while the kept elements are the originals. `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```

```lisp
(remove-if-not #'digit-char-p "a1b2") ; => "12"
```

```lisp
(remove-if-not #'evenp '((1 a) (2 b) (3 c)) :key #'car) ; => ((2 B))
```

```lisp
(remove-if-not #'evenp '(1 2 3 4) :count 1 :from-end t) ; => (1 2 4)
```
