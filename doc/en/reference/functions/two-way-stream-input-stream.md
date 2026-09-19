# two-way-stream-input-stream

`(two-way-stream-input-stream stream)`

The input component of a [two-way stream](make-two-way-stream.md) (an echo
stream answers the same slot).

```lisp
(let ((in (make-string-input-stream "x")))
  (let ((s (make-two-way-stream in (make-string-output-stream))))
    (eq (two-way-stream-input-stream s) in)))
; => T
```
