# concatenated-stream-streams

`(concatenated-stream-streams stream)`

The component list of a [concatenated stream](make-concatenated-stream.md).

```lisp
(let ((a (make-string-input-stream "A")) (b (make-string-input-stream "B")))
  (let ((s (make-concatenated-stream a b)))
    (list (length (concatenated-stream-streams s))
          (eq (car (concatenated-stream-streams s)) a))))
; => (2 T)
```
