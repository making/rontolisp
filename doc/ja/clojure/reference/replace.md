# clojure.string/replace

`(clojure.string/replace s match replacement)`

`match` のすべての出現を `replacement` へ置き換えた `s` を返します。パターン値（`#"..."`、`re-pattern`）はパターンでマッチし、リテラル文字列・文字は文字通りにマッチします（文字は文字と組み合わせます）。パターンに対する文字列の置換は `$` グループを展開します（`$1`、`$0` は全体。`re-quote-replacement` が quote します）。それ以外はマッチに `str` 越しに適用されます。`alias/var` や referred な裸の `replace` としても到達し、関数値としても動きます。

仕様との差異: `match` はパターン値・リテラル文字列・文字のいずれかです。文字列がパターンにコンパイルされることはありません。

```clojure
(println (clojure.string/replace "aaa" "a" "b")) ; bbb
(println (clojure.string/replace "aaa" \a \b)) ; bbb
(println (clojure.string/replace "aaa" "." "b")) ; aaa
(println (clojure.string/replace "aaa" #"a" "b")) ; bbb
(println (clojure.string/replace "abc123def" #"(\\d+)" "<$1>")) ; abc<123>def
```
