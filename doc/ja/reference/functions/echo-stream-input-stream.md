# echo-stream-input-stream

`(echo-stream-input-stream stream)`

[エコーストリーム](make-echo-stream.md)の入力コンポーネント。

```lisp
(let ((in (make-string-input-stream "x")))
  (let ((s (make-echo-stream in (make-string-output-stream))))
    (eq (echo-stream-input-stream s) in)))
; => T
```
