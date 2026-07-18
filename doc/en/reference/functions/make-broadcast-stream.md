# make-broadcast-stream

`(make-broadcast-stream)`

With no component streams, returns a discarding sink: writes to it are dropped (the CL idiom for a null output stream). Component streams (fanning writes out to several streams) are not supported.

```lisp
(let ((s (make-broadcast-stream)))
  (write-string "discarded" s)
  :done) ; => :done
```
