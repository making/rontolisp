# subseq

`(subseq sequence start &optional end)`

Returns a fresh subsequence of `sequence` (a string, a list or a vector) covering the half-open range from index `start` up to but not including `end`, using 0-based indexing. When `end` is omitted the subsequence runs to the end of the sequence. The result has the same kind as the input -- a string for a string, a list for a list, a vector of the same element type for a vector.

The range must satisfy `0 <= start <= end <= (length sequence)` -- for a vector with a fill pointer, the length is the fill pointer. Anything else is an error, `SUBSEQ: invalid bounds START, END for KIND of length N` (`KIND` is `string`, `list` or `vector`), on every backend.

```lisp
(subseq "hello" 1 3) ; => "el"
```
