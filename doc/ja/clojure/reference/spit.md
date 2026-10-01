# spit

`(spit path content)` / `(spit path content :append flag)`

文字列をファイルに書き込み、`nil` で答えます。`:append` なしでは上書き、truthy な
フラグで追記します。インタプリタと JVM で動きます（wasm にファイルシステムはありません）。
値としてはパス・内容と任意のフラグを取ります。

```console
clojure> (spit "/tmp/note.txt" "a\n")
nil
clojure> (spit "/tmp/note.txt" "b" :append true)
nil
```
