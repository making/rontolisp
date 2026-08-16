# output-stream-p

`(output-stream-p stream)`

lite 版: 任意のストリームハンドルと標準出力の指定子 `t` に対して `t` を返します (rontolisp のストリームはどちらの方向にも応答します)。それ以外は nil です。

```lisp
(with-output-to-string (s)
  (princ (output-stream-p s) s)) ; => "T"
```
