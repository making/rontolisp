# read-preserving-whitespace

`(read-preserving-whitespace &optional stream eof-error-p eof-value recursive-p)`

[`read`](read.md) と同じですが、データムを終わらせた空白文字をストリームに残します。データムの直後の文字を知りたい呼び出し側のためのものです。

```lisp
(with-input-from-string (s "12 34")
  (list (read-preserving-whitespace s) (read-char s) (read s)))
; => (12 #\Space 34)
```
