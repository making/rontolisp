# fill

`(fill sequence item &key start end)`

Stores `item` into every element of `sequence` between `:start` (default 0) and `:end` (default the length) and returns the sequence. Destructive, as in CL: a vector -- a general one, a packed `(unsigned-byte 8|16|32)` or float vector, or a string allocated by [`make-string`](make-string.md) or [`make-array`](make-array.md) `:element-type 'character` -- is written in place, and so is a list. A string LITERAL is supported too, but a literal denotes a source constant that is never written on any backend: the fill lands on a fresh string, which comes back as the return value, and `sequence` itself is left holding the original -- the same rule as [`replace`](replace.md). Available on all backends except `--no-gc`.

```lisp
(fill (make-array 5 :element-type '(unsigned-byte 8) :initial-element 9) 0 :start 1 :end 4) ; => #(9 0 0 0 9)
(fill (list 1 2 3) 7) ; => (7 7 7)
```
