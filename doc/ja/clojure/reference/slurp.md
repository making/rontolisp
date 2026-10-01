# slurp

`(slurp path)`

ファイル全体を文字列で返します。インタプリタと JVM で動きます（wasm にファイルシステムは
ありません）。値としては1引数ラムダです。

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
```
