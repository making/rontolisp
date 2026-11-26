# remove-if

`(remove-if predicate sequence &key key start end count from-end)`

Returns a new sequence containing the elements of `sequence` that do **not** satisfy `predicate` (the satisfying elements are removed). The sequence may be a list or a string; a string yields a new string. The original sequence is not modified; use `delete-if` for the destructive version (lists only). With `:key`, the predicate sees the keyed value while the kept elements are the originals. `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length. A `:count` that is neither an integer nor nil signals a `type-error` the same way, before the bounds are checked; a negative count acts as zero and nil is no limit.

```lisp
(remove-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(remove-if #'digit-char-p "a1b2") ; => "ab"
```

```lisp
(remove-if #'evenp '((1 a) (2 b) (3 c)) :key #'car) ; => ((1 A) (3 C))
```

```lisp
(remove-if #'evenp '(1 2 3 4) :count 1) ; => (1 3 4)
```
