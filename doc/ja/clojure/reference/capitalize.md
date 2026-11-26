# clojure.string/capitalize

`(clojure.string/capitalize s)`

最初の文字を大文字に、残りを小文字にした `s` を返します。どちらも `String` の `toUpperCase`・`toLowerCase` と同じ変換です。関数値としても動きます。

```clojure
(println (clojure.string/capitalize "hi there")) ; Hi there
(println (clojure.string/capitalize "hELLO")) ; Hello
(println (clojure.string/capitalize "ßa")) ; SSa
```
