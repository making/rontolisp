# dotimes

`(dotimes [name n] body...)`

`name` を `0` から `n` 未満へ順に束縛し、効果のために本体を実行して `nil` を答えます。
カウントは先に `truncate` を通ります（オラクルの `intCast` と同様）:`2.5` は `0 1` を
数え、非数はシグナルします。

```clojure
(println (dotimes [i 3] (print i))) ; 012nil
(println (dotimes [i 0] :zero)) ; nil
```
