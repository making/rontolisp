# parse-double

`(parse-double s)`

浮動小数点の要素を持つ文字列を読み `Double` 値を返します。読めないときは `nil` です。オラクルの `Double/valueOf` の文法そのもので、ホスト呼び出しを使わないため、すべてのバックエンドで動きます。前後の空白を取り除き、符号のあとに `NaN`・`Infinity`・10 進または 16 進の浮動小数点数と、任意の `d`/`D`/`f`/`F` 接尾辞が付きます（接尾辞で float を経由することはありません。`0.1f` は `0.1` です）。文字列でないものはオラクルと同じ `IllegalArgumentException`（`Expected string, got ...`）です。値としては 1 引数の関数です。

```clojure
(println (parse-double "1.5") (parse-double "1e10") (parse-double "0x1.0p3")) ; 1.5 1.0E10 8.0
(println (parse-double "NaN") (parse-double "-Infinity")) ; ##NaN ##-Inf
(println (map parse-double ["1.5" "xx" "0x10"])) ; (1.5 nil nil)
```
