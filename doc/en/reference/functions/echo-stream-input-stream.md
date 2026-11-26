# echo-stream-input-stream

`(echo-stream-input-stream stream)`

The input component of an [echo stream](make-echo-stream.md).

```lisp
(let ((in (make-string-input-stream "x")))
  (let ((s (make-echo-stream in (make-string-output-stream))))
    (eq (echo-stream-input-stream s) in)))
; => T
```
