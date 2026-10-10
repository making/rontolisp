# slurp

`(slurp f & opts)`

`f` の全体を文字列で返します。パスならそのファイルを読む間だけ開いて閉じ、それ以外は
[clojure.java.io](clojure-java-io.md) のリーダーが `f`（File、URL、バイトストリーム、`IOFactory`
に拡張した型）から読むものを、`:encoding` の文字セット（既定は UTF-8）で返します。clojure.java.io
をロードするプログラムと `http:` URL を読むプログラムでは、URL を綴った文字列はその URL です。
`http:` URL は `rontolisp:fetch` で読むので（[HTTP の URL](clojure-java-io.md#http-urls)）、
`(slurp "https://...")` は応答を読みます。すべてのバックエンドで動きます。wasm ではファイルにそれを含む `--dir` プリオープンが必要です。開かれたリーダー（`clojure.java.io/reader`、Ring の
リクエスト [`:body`](ring.md)）は、どのバックエンドでもオラクルと同じく終端まで読んで
閉じます。その後の読み取りは `java.io.IOException: Stream closed` です（Ring の `:body`
ならボディの終端を返します）。その後の `close`（`with-open` のものなど）は何もしません。
値としては1引数関数です。

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
clojure> (slurp "/tmp/latin1.txt" :encoding "ISO-8859-1")
"é"
```
