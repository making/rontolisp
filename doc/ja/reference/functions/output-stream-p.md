# output-stream-p

`(output-stream-p stream)`

lite 版: 任意のストリームハンドルと標準出力の指定子 `t` に対して `t` を返します (rontolisp のストリームはどちらの方向にも応答します)。それ以外は nil です。[Gray ストリーム](../../guides/gray-streams.md)のインスタンスだけは厳密で、クラスが `rontolisp:fundamental-output-stream` を継承している場合にのみ `t` を返します。

```lisp
(with-output-to-string (s)
  (princ (output-stream-p s) s)) ; => "T"
```
