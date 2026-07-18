# replace

`(replace sequence-1 sequence-2 &key start1 end1 start2 end2)`

Copies the elements of `sequence-2` (bounded by `:start2`/`:end2`) into `sequence-1` (bounded by `:start1`/`:end1`) and returns the result. The number of elements copied is the smaller of the two bounded lengths. A vector `sequence-1` -- including a fill-pointered mutable string ([`make-array`](make-array.md) `:element-type 'character` with `:fill-pointer`/`:adjustable`) -- is mutated in place and returned, as in CL. A simple string `sequence-1` is supported too (the case cl-who needs), but on the compiled backends it is an immutable value, so `replace` returns a fresh string instead of mutating it (the interpreter mutates it in place). Available on all backends except `--no-gc`.

```lisp
(replace (make-string 5 :initial-element #\a) "XY" :start1 1) ; => "aXYaa"
```
