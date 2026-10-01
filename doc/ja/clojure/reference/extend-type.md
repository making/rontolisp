# extend-type

`(extend-type Type Protocol (method [target & args] body...) ...)`

1つの型についてプロトコルの表へ行を足します。`extend-protocol` が格納するのと
同じ行を、プロトコル名の下にまとめます。プロトコル名なしのメソッド群は拒否され
ます（照合すべきインターフェースがありません）。インラインの
`defrecord`・`deftype` 本体と違い、メソッドに型のフィールドはローカルとして見え
ません。

```clojure
(defprotocol P (m [x]))
(extend-type String P (m [s] :str))
(println (m "s")) ; :str
```
