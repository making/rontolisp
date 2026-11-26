# doseq

`(doseq [binding...] body...)`

束縛の組合せごとに本体を左から右へ 1 回ずつ実行し、`nil` を答えます。各ペアはコレク
ションの seq ビュー上にパターンを束縛します（リスト、ベクター、文字列、マップ、
セット、`nil`、1 要素ずつ進む lazy seq）。パターンは `let` と同様に分配束縛します。`:when`/`:while`/`:let`
修飾子は束縛の後に順に続きます:`:when` は要素を飛ばし、`:while` はそのレベルのループを
終え（外側のレベルのものは全体を終える）、`:let` は逐次に束縛します。空ベクターでは本体
を 1 回実行し、`nil` に対しては一度も実行しません。

```clojure
(println (doseq [x [1 2] y [3 4]] (print [x y]))) ; [1 3][1 4][2 3][2 4]nil
(println (doseq [x [1 2 3] :when (odd? x)] (print x))) ; 13nil
(println (doseq [x (iterate inc 0) :while (< x 3)] (print x))) ; 012nil
```
