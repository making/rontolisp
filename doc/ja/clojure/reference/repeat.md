# repeat

`(repeat x)` / `(repeat n x)`

`x` の無限 lazy seq、または（カウント付きで）`x` の `n` 個のコピーの strict リストを返します。0以下のカウントはオラクルと同様に `nil` です。有限アリティはオラクル通りに表示されます。無限の方は `take` 越しにだけ終了します。

```clojure
(println (take 5 (repeat 3))) ; (3 3 3 3 3)
(println (repeat 3 :x))       ; (:x :x :x)
```
