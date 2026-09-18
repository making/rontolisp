# force

`(force promise)`

`promise` の値を返します。最初の 1 回だけその式を評価し、値を記憶します。プロミスでないオブジェクトを `force` するとそのオブジェクト自身を返します。

```scheme
(force (delay (+ 1 2))) ; => 3
(force (make-promise 7)) ; => 7
(force 5) ; => 5
```
