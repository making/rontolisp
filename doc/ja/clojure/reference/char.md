# char

`(char x)`

文字はそのまま、数値は char の範囲 `0..65535` にあるものの `code-char` で答えます
(double は範囲の端を越えると拒否され、それ以外の数値は先に切り捨てられます)。
範囲外の数値、nil、その他は oracle と同様にシグナルします。`int` で読み戻せます。
値としては1引数ラムダです。

```clojure
(println (char 97)) ; a
(println (int (char 97))) ; 97
```
