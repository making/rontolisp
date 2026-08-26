# ffi:open

`(ffi:open &optional name-or-path)`

Opens a shared library and answers an integer handle for it. With no
argument -- or `nil` -- the handle is the PROCESS's own symbols, which is
handle `0` and needs nothing loaded. The name is what the platform's loader
takes (`"libsqlite3.so.0"`, `"libm.dylib"`, an absolute path); a library
that will not open signals. Part of the JVM-only `ffi` package, the foreign
primitives [upstream CFFI](../../guides/cffi.md) is bound to -- a binding
wants `cffi:defcfun`, not these verbs.

```lisp
(ffi:open)
; => 0
```

The process handle sees every symbol the process already has, `strlen` and `getpid` included.
