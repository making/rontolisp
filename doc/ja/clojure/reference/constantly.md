# constantly

`(constantly x)`

引数によらず `x` を返す関数を返します。`x` は一度だけ評価されます。値としては同じ
クロージャの1引数ラムダです。

```clojure
(println ((constantly 7) 1 2 3)) ; 7
```
