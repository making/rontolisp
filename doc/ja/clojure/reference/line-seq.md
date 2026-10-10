# line-seq

`(line-seq path-or-reader)`

行を strict なリストで返します。開かれたリーダー（たとえば `clojure.java.io/reader`）なら閉じずに読み（閉じるのは `with-open` の役目です）、パス、File、URL、バイトストリーム（[clojure.java.io](clojure-java-io.md)）なら開閉しながら読みます。オラクルはリーダーだけを取って遅延ですが、ここでは HTTP の応答に対するリーダー（[HTTP クライアント](http-client.md)の `:as :stream` のボディ、`http:` URL）は遅延で読み、seq がたどり着いた行だけを読みます。それ以外の引数は他の seq 同様 strict に読みます。すべてのバックエンドで動きます。wasm ではファイルにそれを含む `--dir` プリオープンが必要です。値としては1引数ラムダです。

```console
clojure> (line-seq "/tmp/note.txt")
("a" "b")
```
