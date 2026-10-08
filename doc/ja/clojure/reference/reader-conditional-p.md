# reader-conditional?

`(reader-conditional? x)`

`clojure.core/reader-conditional?`: `x` が [reader-conditional](reader-conditional.md) かどうかを返します。これを作るのは `{:read-cond :preserve}` とコンストラクタだけです（ソース中や `:allow` の下のリーダ条件は選ばれた分岐として読まれます）。値としては1引数の関数です。

```clojure
(println (reader-conditional? (read-string {:read-cond :preserve} "#?(:clj 1)")))  ; true
(println (reader-conditional? '(1)))  ; false
```
