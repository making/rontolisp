# mismatch

`(mismatch sequence1 sequence2 &key test key start1 end1 start2 end2 from-end)`

Returns the index **into `sequence1`** of the first position where the two (bounded) sequences differ, or `nil` when they match element for element. `:test` compares elements (`eql` by default) and `:key` selects the compared value; `:start1`/`:end1`/`:start2`/`:end2` bound each sequence. Lite: `:from-end` is accepted but the scan still runs forward, so the returned index is the forward one.

```lisp
(list (mismatch "apple" "apricot") (mismatch '(1 2 3) '(1 2 3))) ; => (2 NIL)
```
