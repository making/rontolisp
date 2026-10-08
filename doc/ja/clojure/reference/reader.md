# clojure.java.io/reader

`(clojure.java.io/reader path-or-reader)` / `[clojure.java.io :as jio]` での `(jio/reader path)`

`slurp` が読むのと同じファイルストリーム実行系で、ファイル上のバッファ付きリーダーを開きます。開かれたリーダー（Ring のリクエスト [`:body`](ring.md)）を渡すと、そのリーダーを返します。`line-seq` は閉じずに行を読み、`with-open` が閉じます。`clojure.string` と同じ配線（`:as`・`:refer`・完全修飾）で使え、`clojure.java.io` の var はこれひとつです（他はエラー）。インタプリタと JVM で動きます。wasm ではパスを含む `--dir` プリオープンが必要です（なければ open が file-error を通知します）。値としては同じ open 上の1引数ラムダです。

```console
clojure> (ns demo (:require [clojure.java.io :as jio]))
nil
clojure> (with-open [r (jio/reader "/tmp/note.txt")] (line-seq r))
("a" "b")
```
