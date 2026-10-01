# char

`(char x)`

文字はそのまま、数値は切り捨てたコードポイントの `code-char` で答えます。それ以外は
oracle と同様にシグナルします。`int` で読み戻せます。値としては1引数ラムダです。

```clojure
(println (char 97)) ; a
(println (int (char 97))) ; 97
```
