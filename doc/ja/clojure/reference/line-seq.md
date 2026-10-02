# line-seq

`(line-seq path-or-reader)`

行を strict なリストで返します。パスなら開閉しながら読み、開かれたリーダー（たとえば `clojure.java.io/reader`）なら閉じずに読みます（閉じるのは `with-open` の役目です）。オラクルはリーダーを取って遅延ですが、ここではどちらの arity も他の seq 同様 strict に読みます。インタプリタと JVM で動きます。wasm ではパスに `--dir` プリオープンが必要です。値としては1引数ラムダです。

```console
clojure> (line-seq "/tmp/note.txt")
("a" "b")
```
