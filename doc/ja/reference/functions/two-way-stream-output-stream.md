# two-way-stream-output-stream

`(two-way-stream-output-stream stream)`

[2way ストリーム](make-two-way-stream.md)の出力コンポーネント (エコーストリームも同じ
スロットを返します)。

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-two-way-stream (make-string-input-stream "x") out)))
    (eq (two-way-stream-output-stream s) out)))
; => T
```
