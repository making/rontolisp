# ffi:free

`(ffi:free pointer)`

Releases memory obtained from [`ffi:alloc`](ffi-alloc.md) and
answers `nil`. Nothing here collects foreign memory, so a block not freed is
leaked for the life of the process; a block freed twice is undefined behavior
in C and is undefined behavior here.

```lisp
(let ((p (ffi:alloc 4)))
  (ffi:poke p :int 7)
  (ffi:free p))
; => NIL
```

A pointer stays a legal value after the free -- it just no longer points at anything you may read.
