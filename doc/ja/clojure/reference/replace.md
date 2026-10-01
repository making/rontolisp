# clojure.string/replace

`(clojure.string/replace s match replacement)`

リテラル文字列か文字の `match` のすべての出現を、文字列か文字の `replacement` へ置き換えた `s` を返します -- 正規表現パターンは拒否され、正規表現の実行時層はありません。`alias/var` や referred な裸の `replace` としても到達し、関数値としても動きます。

仕様との差異: `match` はリテラル文字列か文字で、正規表現パターンにはなりません。

```clojure
(println (clojure.string/replace "aaa" "a" "b")) ; bbb
(println (clojure.string/replace "aaa" \a \b)) ; bbb
(println (clojure.string/replace "aaa" "." "b")) ; aaa
```
