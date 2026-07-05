# print

`(print object &optional stream)`

`object` を読み戻し可能な（`prin1`）形式で標準出力に書き出します。文字列はクオートで囲まれ、文字は `#\` 構文を使い、末尾に改行が付き、その後 `object` を返します。オプションの stream 引数（ファイルストリームまたは `with-output-to-string` の文字列ストリーム）を指定すると、標準出力の代わりにそのストリームに出力されます。別の `read` で解析し直せる、手軽でマシンが読み取り可能な出力に使います。

```lisp
(print "hello")
```

```
"hello"
```
