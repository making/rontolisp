# clojure.java.io/reader

`(clojure.java.io/reader x & opts)` / `[clojure.java.io :as jio]` での `(jio/reader path)`

[clojure.java.io](clojure-java-io.md) の `IOFactory` プロトコルを通して、パス、File、URL、URI、バイトストリームの上のバッファ付きリーダーを開きます。`:encoding` で文字セットを指定します（既定は UTF-8）。開かれたリーダー（Ring のリクエスト [`:body`](ring.md)）を渡すと、そのリーダーを返します。`line-seq` は閉じずに行を読み、`with-open` が閉じます。`clojure.string` と同じ配線（`:as`・`:refer`・完全修飾）で使えます。すべてのバックエンドで動きます。wasm ではファイルにそれを含む `--dir` プリオープンが必要です（なければ open はオラクルの `java.io.FileNotFoundException` です）。値としては同じ引数を取る関数です。

```console
clojure> (ns demo (:require [clojure.java.io :as jio]))
nil
clojure> (with-open [r (jio/reader "/tmp/note.txt")] (line-seq r))
("a" "b")
```
