# replace

`(replace sequence-1 sequence-2 &key start1 end1 start2 end2)`

Copies the elements of `sequence-2` (bounded by `:start2`/`:end2`) into `sequence-1` (bounded by `:start1`/`:end1`) and returns the result. The number of elements copied is the smaller of the two bounded lengths. A vector `sequence-1` -- including a string allocated by [`make-string`](make-string.md) or [`make-array`](make-array.md) `:element-type 'character` -- is mutated in place and returned, as in CL, so the "allocate a buffer, write into it, return it" idiom works on every backend. A string LITERAL is supported as `sequence-1` too (the case cl-who needs), but on the compiled backends it is an immutable value, so `replace` returns a fresh string instead of mutating it (the interpreter mutates it in place). Available on all backends except `--no-gc`.

```lisp
(replace (make-string 5 :initial-element #\a) "XY" :start1 1) ; => "aXYaa"
```
