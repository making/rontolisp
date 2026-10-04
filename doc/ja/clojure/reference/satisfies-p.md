# satisfies?

`(satisfies? Protocol x)`

プロトコルが `x` に届くかどうかです。タグの行、または extend で入れた `Object`
行があれば真です（オラクル同様）。本体でそのプロトコルを挙げた record・deftype・`reify` は、
メソッドの有無によらず満たします。プロトコルは `defmethod` の multimethod 同様
リテラルの名前です。

```clojure
(defprotocol P (m [x]))
(extend-protocol P Object (m [_] :other))
(println (satisfies? P 1))   ; true
(println (satisfies? P nil)) ; true
```
