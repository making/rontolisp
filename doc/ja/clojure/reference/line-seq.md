# line-seq

`(line-seq path-or-reader)`

行を strict なリストで返します。開かれたリーダー（たとえば `clojure.java.io/reader`）なら閉じずに読み（閉じるのは `with-open` の役目です）、パス、File、URL、バイトストリーム（[clojure.java.io](clojure-java-io.md)）なら開閉しながら読みます。オラクルはリーダーだけを取って遅延ですが、ここではどの引数も他の seq 同様 strict に読みます。すべてのバックエンドで動きます。wasm ではファイルにそれを含む `--dir` プリオープンが必要です。値としては1引数ラムダです。

```console
clojure> (line-seq "/tmp/note.txt")
("a" "b")
```
