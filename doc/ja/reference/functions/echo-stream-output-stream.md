# echo-stream-output-stream

`(echo-stream-output-stream stream)`

[エコーストリーム](make-echo-stream.md)の出力コンポーネント。

```lisp
(let ((out (make-string-output-stream)))
  (let ((s (make-echo-stream (make-string-input-stream "x") out)))
    (eq (echo-stream-output-stream s) out)))
; => T
```
