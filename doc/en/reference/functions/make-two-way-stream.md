# make-two-way-stream

`(make-two-way-stream input-stream output-stream)`

One stream over an input and an output component: reads come from the input
stream, writes reach the output stream.

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-two-way-stream (make-string-input-stream "AB") out)))
    (list (read-char s) (write-string "hi" s) (get-output-stream-string out))))
; => (#\A "hi" "hi")
```

A two-way stream is a [Gray stream](../../guides/gray-streams.md), so the whole
read and write protocol dispatch on it. `read-line` and the sequence operators
work over its input side, and `write-char` / `write-string` / `format` / the
print family reach the output side. The components are recoverable with
[`two-way-stream-input-stream`](two-way-stream-input-stream.md) and
[`two-way-stream-output-stream`](two-way-stream-output-stream.md).

Each component must stream in the right direction: an input stream that is not
one, or an output stream that is not one, signals a `type-error`.
