# when-first

`(when-first [x coll] body...)`

パターンを seq ビューの先頭に束縛し、コレクションが非空のときだけ本体を実行します。
コレクションは一度だけ評価されます。

```clojure
(println (when-first [x [1 2]] x)) ; 1
(println (when-first [x []] :body)) ; nil
```
