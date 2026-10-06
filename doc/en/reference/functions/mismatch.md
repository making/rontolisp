# mismatch

`(mismatch sequence1 sequence2 &key test key start1 end1 start2 end2 from-end)`

Returns the index **into `sequence1`** of the first position where the two (bounded) sequences differ, or `nil` when they match element for element. `:test` compares elements (`eql` by default) and `:key` selects the compared value; `:start1`/`:end1`/`:start2`/`:end2` bound each sequence; a nil `:end1`/`:end2` means the length. A bound that is negative, not an integer (a nil `:start1`/`:start2` included) or past its sequence's length, or a start past its end, signals the `type-error` [`subseq`](subseq.md) signals for the same range. Lite: `:from-end` is accepted but the scan still runs forward, so the returned index is the forward one.

```lisp
(list (mismatch "apple" "apricot") (mismatch '(1 2 3) '(1 2 3))) ; => (2 NIL)
```
