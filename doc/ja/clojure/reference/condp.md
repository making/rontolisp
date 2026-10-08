# condp

`(condp pred expr clause... default?)`

`pred` と `expr` を一度ずつ評価し、各節のテストで `(pred test expr)` を順に呼び、呼び出しが truthy になった最初の節の結果を返します。節は `test result` か `test :>> f` で、後者は述語の戻り値を引数に `f` を呼んだ結果を返します。末尾に単独で残ったフォームはデフォルトです。デフォルトがなく、truthy になる節もないときは `IllegalArgumentException` `No matching clause: <expr>` を投げます。

```clojure
(defn size [n] (condp < n 100 :big 10 :medium :small))
(println (map size [500 50 5])) ; (:big :medium :small)
(println (condp some [1 2 3] #{0 6} :>> inc #{2 4} :>> dec)) ; 1
```
