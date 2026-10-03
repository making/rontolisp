# for

`(for [binding...] expr)`

束縛の全組合せに対する `expr` を、左から右へ答えます。ペア、パターン、修飾子は `doseq`
と同様です。各 `:when` は要素を飛ばし、各 `:while` はそのレベルを終え、各 `:let` は逐次に
束縛します。辿るコレクションがすべて strict な間は答えを一度に realize した strict なリスト
です。最初の lazy なコレクション（最初の束縛のものも含む）から先は lazy seq になり、消費
されるにつれて realize されます。そのため `first` と `take` は答える分だけ realize し、無限の
コレクションもその後ろで終わります。

逸脱:空の strict な結果は `nil` で、オラクルが `()` と印字する点と異なります。strict な
答えは `for` の実行時に realize され、オラクルのように消費まで待ちません。

```clojure
(println (for [x [1 2 3] :when (odd? x)] (* x 10))) ; (10 30)
(println (for [x [1 2] y [3 4]] [x y])) ; ([1 3] [1 4] [2 3] [2 4])
(println (for [x [1 2 3 4] :while (< x 3)] x)) ; (1 2)
```
