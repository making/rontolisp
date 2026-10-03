# delay?

`(delay? x)`

`clojure.core/delay?`: どの値にも `false` を返します。`delay` は拒否されるため、ここにはそれに当たる値がありません（それ以外の値に対するオラクルの答えと同じです）。引数は評価されます。値としては1引数の関数です。

```clojure
(println (delay? 1))  ; false
```
