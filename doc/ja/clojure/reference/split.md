# clojure.string/split

`(clojure.string/split s sep)`
`(clojure.string/split s sep limit)`

リテラル文字列 `sep` の周りで `s` を分割します -- 正規表現パターンは拒否され、正規表現の実行時層はありません。正の `limit` は部分数を上限切りし、最後の部分が残りを取ります。負ならすべての部分を残し、それ以外は末尾の空部分を落とします。空の入力は `nil` を返します。`alias/var` や referred な裸の `split` としても到達し、関数値としても動きます。

仕様との差異: パターンはリテラル文字列で、正規表現にはなりません。

```clojure
(println (clojure.string/split "a,b" ",")) ; (a b)
(println (clojure.string/split "a,b," "," -1)) ; (a b )
(println (clojure.string/split "aaa" ".")) ; (aaa)
```
