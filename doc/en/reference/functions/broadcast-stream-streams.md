# broadcast-stream-streams

`(broadcast-stream-streams stream)`

The component list of a [broadcast stream](make-broadcast-stream.md), the write-side sibling of [concatenated-stream-streams](concatenated-stream-streams.md).

```lisp
(let ((a (make-string-output-stream)) (b (make-string-output-stream)))
  (let ((s (make-broadcast-stream a b)))
    (list (length (broadcast-stream-streams s))
          (eq (car (broadcast-stream-streams s)) a))))
; => (2 T)
```
