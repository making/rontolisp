# make-broadcast-stream

`(make-broadcast-stream)`

コンポーネントストリームなしで呼ぶと、書き込みを捨てるシンクを返します (null 出力ストリームの CL イディオム)。コンポーネントストリーム (複数ストリームへの書き込みファンアウト) はサポートされません。

```lisp
(let ((s (make-broadcast-stream)))
  (write-string "discarded" s)
  :done) ; => :done
```
