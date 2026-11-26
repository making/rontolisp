# make-concatenated-stream

`(make-concatenated-stream &rest streams)`

An input stream reading its components in order, dropping each one at its end of
file.

```lisp
(let ((s (make-concatenated-stream (make-string-input-stream "AB")
                                   (make-string-input-stream "CD"))))
  (list (read-char s) (read-char s) (read-char s) (read-char s) (read-char s nil :eof)))
; => (#\A #\B #\C #\D :EOF)
```

The input side is a [Gray stream](../../guides/gray-streams.md), so `read-line`
and `read-char` walk the components seamlessly. The components are recoverable
with [`concatenated-stream-streams`](concatenated-stream-streams.md).

Every component must be an input stream; one that is not signals a `type-error`.
