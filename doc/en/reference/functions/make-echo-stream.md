# make-echo-stream

`(make-echo-stream input-stream output-stream)`

A two-way stream that also writes everything it reads to the output component —
the interactive-echo idiom.

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-echo-stream (make-string-input-stream "ab") out)))
    (list (read-char s) (read-line s) (get-output-stream-string out))))
; => (#\a "b" "ab")
```

The input side is a [Gray stream](../../guides/gray-streams.md), so `read-line`
and `read-char` dispatch over it and each character read is echoed to the output
component along the way; `write-char` / `write-string` reach the output side
directly. The components are recoverable with
[`echo-stream-input-stream`](echo-stream-input-stream.md) and
[`echo-stream-output-stream`](echo-stream-output-stream.md).
