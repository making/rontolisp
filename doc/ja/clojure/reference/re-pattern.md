# re-pattern

`(re-pattern s)`

`s` のパターンを返します。パターンはそのまま返し、文字列はコンパイルします（オラクル同様に即時解析）。関数値としても動きます。

```clojure
(println (re-pattern "a+")) ; #"a+"
(println (str (re-pattern "a+"))) ; a+
```
