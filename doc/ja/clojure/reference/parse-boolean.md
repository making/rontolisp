# parse-boolean

`(parse-boolean s)`

文字列 `"true"` と `"false"` を読み真偽値を返します。それ以外の文字列は `nil` です。文字列でないものはオラクルと同じ `IllegalArgumentException`（`Expected string, got ...`）です。すべてのバックエンドで動きます。値としては 1 引数の関数です。

```clojure
(println (parse-boolean "true") (parse-boolean "false") (parse-boolean "yes")) ; true false nil
(println (map parse-boolean ["true" "xx"])) ; (true nil)
```
