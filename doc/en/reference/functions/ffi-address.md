# ffi:address

`(ffi:address pointer-or-integer)`

Converts between a foreign pointer and its integer address, in
whichever direction the argument asks for -- so it is its own inverse, and
`cffi:make-pointer`, `cffi:pointer-address` and `cffi:null-pointer` all fall
out of this one verb. An address is UNSIGNED 64-bit on both sides, so one at
or above 2^63 round-trips rather than coming back negative.

```lisp
(ffi:address (ffi:address 4096))
; => 4096
```

Address `0` is a legal NULL pointer, not an error -- `(ffi:address 0)` is what `cffi:null-pointer` answers.
