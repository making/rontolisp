# remove-method

`(remove-method multifn dispatch-value)`

`dispatch-value` の下に格納されたメソッドを multimethod の表から削除します。multimethodを返し、以降の呼び出しは再びディスパッチ検索を通ります。

```clojure
(defmulti r :k)
(defmethod r :x [m] 1)
(remove-method r :x)
(println (if (get-method r :x) :yes :no)) ; no
```
