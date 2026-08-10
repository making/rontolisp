# fill

`(fill sequence item &key start end)`

Stores `item` into every element of `sequence` between `:start` (default 0) and `:end` (default the length) and returns the sequence. Destructive, as in CL: a vector -- a general one, a packed `(unsigned-byte 8|16|32)` or float vector, or a string allocated by [`make-string`](make-string.md) or [`make-array`](make-array.md) `:element-type 'character` -- is written in place, and so is a list. A string LITERAL is supported too, but on the compiled backends it is an immutable value, so `fill` returns a fresh string instead of mutating it (the interpreter mutates in place) -- the same deviation as [`replace`](replace.md). Available on all backends except `--no-gc`.

```lisp
(fill (make-array 5 :element-type '(unsigned-byte 8) :initial-element 9) 0 :start 1 :end 4) ; => #(9 0 0 0 9)
(fill (list 1 2 3) 7) ; => (7 7 7)
```
