# ffi:size

`(ffi:size type)`

Answers the size in bytes of a foreign type. The C integer names are
their LP64 widths (`:long` is 8), and a `(:struct member...)` designator is
laid out with the C padding rule, so its size includes the tail padding.

```lisp
(list (ffi:size :int) (ffi:size :pointer) (ffi:size '(:struct :int :double)))
; => (4 8 16)
```

`(:struct :int :double)` is 16 rather than 12: the double is aligned to 8, and the struct's size is a multiple of its alignment.
