# ffi:alloc

`(ffi:alloc size)`

Allocates `size` bytes of foreign memory and answers a pointer to
it. This is `malloc`: the memory outlives every Lisp scope and is released
only by [`ffi:free`](ffi-free.md), which is CFFI's own contract.

```lisp
(let ((p (ffi:alloc 8)))
  (ffi:poke p :int 42)
  (prog1 (ffi:peek p :int) (ffi:free p)))
; => 42
```

The contents are undefined until written; `prog1` here reads the value back before the block is freed.
