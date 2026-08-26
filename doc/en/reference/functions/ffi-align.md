# ffi:align

`(ffi:align type)`

Answers the alignment in bytes a foreign type requires. A
structure's alignment is the widest of its members', which is what the
padding rule [`ffi:size`](ffi-size.md) applies is built on.

```lisp
(list (ffi:align :char) (ffi:align :double) (ffi:align '(:struct :char :double)))
; => (1 8 8)
```

Alignment and size are asked separately because a struct passed by value needs both to be right.
