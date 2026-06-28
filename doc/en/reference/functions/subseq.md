# subseq

`(subseq sequence start &optional end)`

Returns a fresh subsequence of `sequence` (a string or a list) covering the half-open range from index `start` up to but not including `end`, using 0-based indexing. When `end` is omitted the subsequence runs to the end of the sequence. The result has the same type as the input -- a string for a string, a list for a list.

```lisp
(subseq "hello" 1 3) ; => "el"
```
