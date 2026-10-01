# line-seq

`(line-seq path)`

ファイルの行を strict なリストで返します。オラクルはリーダーを取って遅延ですが、ここでは
他の seq 同様パスから strict に読みます。インタプリタと JVM で動きます。値としては1引数
ラムダです。

```console
clojure> (line-seq "/tmp/note.txt")
("a" "b")
```
