# file-seq

`(file-seq dir)`

`java.io.File` の `dir` と、それがディレクトリならその下のすべての File の遅延 seq です。深さ
優先で、各ディレクトリはその中身より先に来ます（オラクルの `isDirectory` と `listFiles` による
`tree-seq`）。ディレクトリ内の File はオラクルと同じく、ホストが列挙する順のままで並べ替えません。
File 以外はオラクルの `ClassCastException` です。すべてのバックエンドで動きます。wasm では
ツリーを含む `--dir` プリオープンが必要です。値としては1引数関数です。File は
[clojure.java.io](clojure-java-io.md) のものです。

```console
clojure> (require '[clojure.java.io :as io])
nil
clojure> (sort (map str (file-seq (io/file "/tmp/notes"))))
("/tmp/notes" "/tmp/notes/a.txt" "/tmp/notes/b.txt")
clojure> (filter #(.isFile %) (file-seq (io/file "/tmp/notes")))
(#object[java.io.File "/tmp/notes/b.txt"] #object[java.io.File "/tmp/notes/a.txt"])
```
