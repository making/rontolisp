# ffi:call

`(ffi:call address return-type argument-types args...)`

Calls a C function at `address` with the given return type and
argument-type list, marshalling each argument and the answer. The whole
calling convention is decided at RUN time, which is what lets
`cffi:defcfun` invent a shape in your program. Type designators are the CFFI
keywords (`:char` .. `:ullong`, `:float`, `:double`, `:pointer`, `:string`,
`:void`, `:int8` .. `:uint64`), plus `(:struct member...)` for a structure by
value and `:varargs` marking where a variadic tail starts.

```lisp
(ffi:call (ffi:symbol (ffi:open) "strlen") :long '(:string) "hello")
; => 5
```

A `:string` argument is copied into foreign memory for the call and freed after; a `:string` return reads the NUL-terminated UTF-8 back.
