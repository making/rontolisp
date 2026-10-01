# clojure.string/replace-first

`match` の最初の 1 回だけを置き換えた `s` を返します。パターン値（`#"..."`、`re-pattern`）はパターンでマッチし、リテラル文字列・文字は文字通りにマッチします。パターンに対する文字列の置換は `$` グループを展開し、それ以外はマッチに `str` 越しに適用されます。関数値としても動きます。

仕様との差異: `match` はパターン値・リテラル文字列・文字のいずれかです。文字列がパターンにコンパイルされることはありません。

```clojure
(println (clojure.string/replace-first "aaa" "a" "b")) ; baa
(println (clojure.string/replace-first "aaa" "a+" "b")) ; aaa
(println (clojure.string/replace-first "aaa" #"a+" "b")) ; b
```
