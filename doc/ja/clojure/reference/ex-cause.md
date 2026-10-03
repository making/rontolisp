# ex-cause

`(ex-cause ex)`

例外の cause を返します。`ex-info` の第 3 引数、または throwable の構築が受け取った cause（`(Exception. "m" cause)`、`(Exception. cause)`）です。cause がない例外、実行時エラー、例外でない値では `nil` です。`.getCause` も同じ値を返します。関数値としても動きます。

```clojure
(println (ex-message (ex-cause (ex-info "outer" {} (Exception. "inner"))))) ; inner
(println (map ex-cause [(Exception. "x") "s"])) ; (nil nil)
```
