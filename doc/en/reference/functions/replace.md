# replace

`(replace sequence-1 sequence-2 &key start1 end1 start2 end2)`

Copies the elements of `sequence-2` (bounded by `:start2`/`:end2`) into `sequence-1` (bounded by `:start1`/`:end1`) and returns the result. The number of elements copied is the smaller of the two bounded lengths. String sequences are supported (the case cl-who needs); because strings are immutable values, `replace` returns a fresh string rather than mutating `sequence-1` in place. Available on all backends except `--no-gc`.

```lisp
(replace (make-string 5 :initial-element #\a) "XY" :start1 1) ; => "aXYaa"
```
