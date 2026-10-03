# decimal?

`(decimal? x)`

`clojure.core/decimal?`: どの値にも `false` を返します。`M` リテラルは正確な有理数として読まれる（`1.5M` は `3/2`）ため、decimal という種類がありません（オラクルは `1.5M` に `true` を返します）。引数は評価されます。値としては1引数の関数です。

```clojure
(println (decimal? 1.5M) (decimal? 1.5))  ; false false
```
