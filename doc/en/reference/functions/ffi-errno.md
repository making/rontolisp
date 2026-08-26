# ffi:errno

`(ffi:errno)`

Answers the `errno` value the calling thread's last
[`ffi:call`](ffi-call.md) left. It is CAPTURED by the call itself rather than
fetched afterwards, so nothing between the call and this read can overwrite
it, and each thread has its own.

```lisp
(progn
  (ffi:call (ffi:symbol (ffi:open) "open") :int '(:string :int) "/no/such/file" 0)
  (ffi:errno))
; => 2
```

`2` is `ENOENT`. On other implementations this needs a helper library; here the capture is part of every downcall.
