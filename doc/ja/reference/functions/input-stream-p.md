# input-stream-p

`(input-stream-p stream)`

ストリームから読み込めるときに `t` を返します。文字列入力ストリーム、`:input` または `:io` で開いたファイルストリーム、ソケット、標準入力ストリームは `t` を返し、文字列出力ストリーム、出力専用で開いたファイルストリーム、クローズ済みのファイルストリームは nil を返します。シノニムストリームは、その時点で転送先となっているストリームの答えを返します。指定子 `t` はどちらの方向にも `t` を返し、ストリームでないものは nil を返します。[Gray ストリーム](../../guides/gray-streams.md)のインスタンスは、クラスが `rontolisp:fundamental-input-stream` を継承している場合に `t` を返します。

```lisp
(with-input-from-string (s "x")
  (input-stream-p s)) ; => T
```

```lisp
(input-stream-p (make-string-output-stream)) ; => NIL
```
