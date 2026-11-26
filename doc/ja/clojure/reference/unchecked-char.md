# unchecked-char

`(unchecked-char x)`

文字はそのまま、数値は下位16ビットをコードとする文字を返します（doubleは64ビットの範囲へ飽和させて切り捨てます）。`nil` とその他の非数はシグナルします。値としては1引数の関数です。

```clojure
(println (unchecked-char 97) (unchecked-char 97.5) (unchecked-char 65633)) ; a a a
```
