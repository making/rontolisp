# echo-stream-output-stream

`(echo-stream-output-stream stream)`

The output component of an [echo stream](make-echo-stream.md).

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-echo-stream (make-string-input-stream "x") out)))
    (eq (echo-stream-output-stream s) out)))
; => T
```
