# spit

`(spit f content)` / `(spit f content :append flag :encoding name)`

`(str content)` を `f` に書き込み、`nil` で答えます。文字列はそのまま、
それ以外の値は `str` の表記で書き込まれ（`(spit f '(1 2))` は `(1 2)` を書く。
コレクション内の文字列はクォートされるので [read](read.md) で読み戻せる）、
`nil` は何も書き込みません。`f` はパスか、[clojure.java.io](clojure-java-io.md) のライターが
開くもの（File、バイトストリーム、`IOFactory` に拡張した型）です。`:append` なしでは上書き、
truthy なフラグで追記します。`:encoding` で文字セットを指定します（既定は UTF-8）。すべての
バックエンドで動きます。wasm ではファイルにそれを含む `--dir` プリオープンが必要です。
値としてはパス・内容と任意のフラグを取ります。

```console
clojure> (spit "/tmp/note.txt" "a\n")
nil
clojure> (spit "/tmp/note.txt" "b" :append true)
nil
clojure> (spit "/tmp/note.txt" '(1 2))
nil
```
