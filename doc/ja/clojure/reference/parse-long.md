# parse-long

`(parse-long s)`

10 進の数字（先頭の `-`/`+` 付き）を読み `Long` 値を返します。読めないときは `nil` です。オラクルの `Long/valueOf` の文法そのもので、ホスト呼び出しを使わないため、すべてのバックエンドで動きます（移植性のあるプログラムは `read-string` 以外に数の読み取り手段がありませんでした）。数字は ASCII でも Unicode の 10 進数字でもよく、long の範囲を超えるものや、それ以外の綴りは `nil` です。文字列でないものはオラクルと同じ `IllegalArgumentException`（`Expected string, got ...`）です。値としては 1 引数の関数です。

```clojure
(println (parse-long "12") (parse-long "-12") (parse-long "+12")) ; 12 -12 12
(println (parse-long "007") (parse-long "9223372036854775807")) ; 7 9223372036854775807
(println (map parse-long ["12" "xx" "9223372036854775808"])) ; (12 nil nil)
```
