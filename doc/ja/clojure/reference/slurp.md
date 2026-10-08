# slurp

`(slurp path-or-reader)`

ファイル全体を文字列で返します。インタプリタと JVM で動きます。wasm ではパスを含む
`--dir` プリオープンが必要です。開かれたリーダー（`clojure.java.io/reader`、Ring の
リクエスト [`:body`](ring.md)）は、どのバックエンドでもオラクルと同じく終端まで読んで
閉じます。その後の読み取りは `java.io.IOException: Stream closed` です（Ring の `:body`
ならボディの終端を返します）。その後の `close`（`with-open` のものなど）は何もしません。
値としては1引数関数です。

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
```
