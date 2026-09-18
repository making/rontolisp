# read-string

`(read-string k [port])`

テキスト入力ポート `port`（省略時は現在の入力ポート）から最大 `k` 文字を読んで文字列で返します。入力の終わりではそれより少なく、1 文字も残っていなければファイル終端オブジェクトを返します。`(read-string 0)` は `""` を返します。

```scheme
(read-string 3 (open-input-string "abcdef")) ; => "abc"
```
