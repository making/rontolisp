# interactive-stream-p

`(interactive-stream-p stream)`

Whether the stream is attached to an interactive device. Always nil here: no backend distinguishes a terminal from a pipe. The argument is a stream, not a stream designator -- anything else is a `type-error`.

```lisp
(with-output-to-string (s) (princ (interactive-stream-p s) s)) ; => "NIL"
```
