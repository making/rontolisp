# ffi:pointerp

`(ffi:pointerp value)`

Answers `t` when `value` is a foreign pointer. A pointer is its own
kind of value here, not an integer -- so this answers `nil` for `42`, even
though a plain integer is still accepted wherever an address is expected.

```lisp
(list (ffi:pointerp (ffi:address 4096)) (ffi:pointerp 42))
; => (T NIL)
```

`cffi:pointerp` is this verb, and it is why a wrong operand at the boundary is a type error rather than a wild address.
