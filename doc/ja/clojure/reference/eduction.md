# eduction

`(eduction xform* coll)`

`comp` と同じ順に合成したトランスデューサーへ `coll` を通した結果を返します。値としては
トランスデューサー列とコレクション1つを取ります。

仕様との差異: eduction はその seq で、一度だけ計算します（strict な入力には strict に、
lazy な入力には lazy に）。オラクルは reduce のたびに変換をやり直します。`println` は
seq として表示し、オラクルはオブジェクトを表示します（`prn` は一致します）。

```clojure
(prn (eduction (filter odd?) (range 6))) ; (1 3 5)
(prn (eduction (map inc) (filter even?) [1 2 3 4])) ; (2 4)
(println (reduce + 0 (eduction (map inc) [1 2 3]))) ; 9
```
