# input-stream-p

`(input-stream-p stream)`

lite 版: 任意のストリームハンドルと標準出力の指定子 `t` に対して `t` を返します (rontolisp のストリームはどちらの方向にも応答します)。それ以外は nil です。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(with-input-from-string (s "x")
  (input-stream-p s)) ; => t
```
