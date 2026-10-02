# spit

`(spit path content)` / `(spit path content :append flag)`

`(str content)` をファイルに書き込み、`nil` で答えます。文字列はそのまま、
それ以外の値は `str` の表記で書き込まれ（`(spit f '(1 2))` は `(1 2)` を書く）、
`nil` は何も書き込みません。`:append` なしでは上書き、truthy な
フラグで追記します。インタプリタと JVM で動きます。wasm ではパスを含む `--dir` プリオープンが必要です。
値としてはパス・内容と任意のフラグを取ります。

```console
clojure> (spit "/tmp/note.txt" "a\n")
nil
clojure> (spit "/tmp/note.txt" "b" :append true)
nil
clojure> (spit "/tmp/note.txt" '(1 2))
nil
```
