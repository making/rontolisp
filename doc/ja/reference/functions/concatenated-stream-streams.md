# concatenated-stream-streams

`(concatenated-stream-streams stream)`

[連結ストリーム](make-concatenated-stream.md)のコンポーネントリスト。

```lisp
(let ((a (make-string-input-stream "A")) (b (make-string-input-stream "B")))
  (let ((s (make-concatenated-stream a b)))
    (list (length (concatenated-stream-streams s))
          (eq (car (concatenated-stream-streams s)) a))))
; => (2 T)
```
