# when-let

`(when-let [p e] body...)`

`let` 同様にパターンを束縛し（分割束縛を含む）、初期値が truthy のときだけ本体を実行します。
初期値は一度だけ評価されます。

```clojure
(println (when-let [x 1] (+ x 10))) ; 11
(println (when-let [x nil] :body)) ; nil
```
