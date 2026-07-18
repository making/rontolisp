# make-broadcast-stream

`(make-broadcast-stream)`

With no component streams, returns a discarding sink: writes to it are dropped (the CL idiom for a null output stream). Component streams (fanning writes out to several streams) are not supported.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(let ((s (make-broadcast-stream)))
  (write-string "discarded" s)
  :done) ; => :done
```
