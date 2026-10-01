# doto

`(doto expr step...)`

`expr` を FIRST 引数として 1 つの一時変数を囲むすべてのステップへ通し、その（変化しない）対象を返します -- ステップは値への作用のために走り、リストのステップは `->` のように挿入位置の head に対象を取ります。

```clojure
(println (doto 5 (inc) (dec))) ; 5
```
