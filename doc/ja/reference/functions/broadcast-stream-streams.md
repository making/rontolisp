# broadcast-stream-streams

`(broadcast-stream-streams stream)`

[ブロードキャストストリーム](make-broadcast-stream.md)のコンポーネントリスト。書き込み側における [concatenated-stream-streams](concatenated-stream-streams.md) にあたります。

```lisp
(let ((a (make-string-output-stream)) (b (make-string-output-stream)))
  (let ((s (make-broadcast-stream a b)))
    (list (length (broadcast-stream-streams s))
          (eq (car (broadcast-stream-streams s)) a))))
; => (2 T)
```
