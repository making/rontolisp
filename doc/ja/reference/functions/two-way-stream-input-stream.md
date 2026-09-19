# two-way-stream-input-stream

`(two-way-stream-input-stream stream)`

[2way ストリーム](make-two-way-stream.md)の入力コンポーネント (エコーストリームも同じ
スロットを返します)。

```lisp
(let ((in (make-string-input-stream "x")))
  (let ((s (make-two-way-stream in (make-string-output-stream))))
    (eq (two-way-stream-input-stream s) in)))
; => T
```
