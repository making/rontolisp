# slurp

`(slurp path-or-reader)`

ファイル全体を文字列で返します。インタプリタと JVM で動きます。wasm ではパスを含む
`--dir` プリオープンが必要です。開かれたリーダー（`clojure.java.io/reader`、Ring の
リクエスト [`:body`](ring.md)）は、どのバックエンドでも終端まで読み、閉じずに持ち主へ
残します（オラクルは閉じます）。値としては1引数関数です。

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
```
