# output-stream-p

`(output-stream-p stream)`

ストリームへ書き込めるときに `t` を返します。文字列出力ストリーム、出力用 (`:output`、`:append`、`:overwrite`) または `:io` で開いたファイルストリーム、ソケット、標準出力ストリーム、`*error-output*` は `t` を返し、文字列入力ストリーム、`:input` で開いたファイルストリーム、クローズ済みのファイルストリームは nil を返します。シノニムストリームは、その時点で転送先となっているストリームの答えを返します。指定子 `t` はどちらの方向にも `t` を返し、ストリームでないものは nil を返します。[Gray ストリーム](../../guides/gray-streams.md)のインスタンスは、クラスが `rontolisp:fundamental-output-stream` を継承している場合に `t` を返します。

```lisp
(with-output-to-string (s)
  (princ (output-stream-p s) s)) ; => "T"
```

```lisp
(output-stream-p (make-string-input-stream "abc")) ; => NIL
```
