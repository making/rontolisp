# ffi:callback

`(ffi:callback function return-type argument-types)`

Turns a Lisp function into a C function pointer of the given shape,
so C code can call back into Lisp. The stub lives for the whole program. An
error escaping the callback would unwind into the C frame above it and end the
process, so one never does: the message is printed and the callback answers
zero of its declared type. `:string` and struct types are refused in a
callback shape -- take a `:pointer`.

```lisp
(ffi:pointerp (ffi:callback (lambda (a b) (- a b)) :int '(:int :int)))
; => T
```

Redefining a callback answers a NEW address: a stub cannot be re-targeted, so a C side holding the old one keeps calling the old function.
