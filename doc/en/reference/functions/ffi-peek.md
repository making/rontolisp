# ffi:peek

`(ffi:peek pointer type &optional offset)`

Reads one value of `type` at `pointer` plus `offset` bytes. A
`:string` peek reads the NUL-terminated UTF-8 at the location; a `:pointer`
peek answers a foreign pointer.

```lisp
(let ((p (ffi:alloc 16)))
  (ffi:poke p :int 1)
  (ffi:poke p :int 2 8)
  (prog1 (list (ffi:peek p :int) (ffi:peek p :int 8)) (ffi:free p)))
; => (1 2)
```

The offset is in BYTES, not elements -- an array walk multiplies by `(ffi:size type)` itself.
