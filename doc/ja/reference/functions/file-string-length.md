# file-string-length

`(file-string-length stream object)`

オブジェクト (文字または文字列) を書き込んだときにストリームの [file-position](file-position.md) がどれだけ進むかを返します。どのバックエンドも UTF-8 だけを書き出すため、値は UTF-8 でのバイト長です。

```lisp
(with-output-to-string (s) (princ (list (file-string-length s #\a)
                                        (file-string-length s "abc"))
                                  s))
; => "(1 3)"
```
