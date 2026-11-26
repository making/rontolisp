# ffi:symbol

`(ffi:symbol library "name")`

Answers the address of a symbol in an open library as a foreign
pointer, or `nil` when the library does not have it. `library` is a handle
from [`ffi:open`](ffi-open.md).

```lisp
(ffi:pointerp (ffi:symbol (ffi:open) "strlen"))
; => T
```

A missing symbol is `nil`, not an error -- which is what lets a binding probe for an optional entry point.
