# clojure.string/split

`(clojure.string/split s sep)`
`(clojure.string/split s sep limit)`

`sep` の周りで `s` を分割します。パターン値（`#"..."`、`re-pattern`）はマッチの周りで分割し、リテラル文字列・文字は文字通りに分割します。正の `limit` は部分数を上限切りし、最後の部分が残りを取ります。負ならすべての部分を残し、それ以外は末尾の空部分を落とします。空の入力はリテラル区切りで `nil`、パターンで空1部分を返します（オラクル通り）。`alias/var` や referred な裸の `split` としても到達し、関数値としても動きます。

仕様との差異: 答えは seq でありベクターにはなりません。文字列区切りは文字通りのままで、パターンにはなりません。

```clojure
(println (clojure.string/split "a,b" ",")) ; (a b)
(println (clojure.string/split "a,b," "," -1)) ; (a b )
(println (clojure.string/split "aaa" ".")) ; (aaa)
(println (clojure.string/split "a,b" #",")) ; (a b)
(println (clojure.string/split "a b  c" #"\\s+")) ; (a b c)
```
