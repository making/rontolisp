# two-way-stream-output-stream

`(two-way-stream-output-stream stream)`

The output component of a [two-way stream](make-two-way-stream.md) (an echo
stream answers the same slot).

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-two-way-stream (make-string-input-stream "x") out)))
    (eq (two-way-stream-output-stream s) out)))
; => T
```
