# clojure.string/replace-first

`(clojure.string/replace-first s match replacement)`

リテラル文字列か文字の `match` の最初の 1 回だけを置き換えた `s` を返します。関数値としても動きます。

Deviation: `match` はリテラル文字列か文字で、正規表現パターンにはなりません。

```clojure
(println (clojure.string/replace-first "aaa" "a" "b")) ; baa
(println (clojure.string/replace-first "aaa" "a+" "b")) ; aaa
```
