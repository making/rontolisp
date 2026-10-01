# for

`(for [binding...] expr)`

束縛の全組合せに対する `expr` の strict なリストを、左から右へ答えます。ペア、パターン、
修飾子は `doseq` と同様です。各 `:when` は要素を飛ばし、各 `:while` はそのレベルの
ループを終え、各 `:let` は逐次に束縛します。

逸脱:空の結果は `nil` で、オラクルが `()` と印字する点と異なります。

```clojure
(println (for [x [1 2 3] :when (odd? x)] (* x 10))) ; (10 30)
(println (for [x [1 2] y [3 4]] [x y])) ; ([1 3] [1 4] [2 3] [2 4])
(println (for [x [1 2 3 4] :while (< x 3)] x)) ; (1 2)
```
