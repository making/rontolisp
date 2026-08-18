# input-stream-p

`(input-stream-p stream)`

lite 版: 任意のストリームハンドルと標準出力の指定子 `t` に対して `t` を返します (rontolisp のストリームはどちらの方向にも応答します)。それ以外は nil です。[Gray ストリーム](../../guides/gray-streams.md)のインスタンスだけは厳密で、クラスが `rontolisp:fundamental-input-stream` を継承している場合にのみ `t` を返します。

```lisp
(with-input-from-string (s "x")
  (input-stream-p s)) ; => T
```
