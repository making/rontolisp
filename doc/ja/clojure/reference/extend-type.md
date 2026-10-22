# extend-type

`(extend-type Type Protocol (method [target & args] body...) ...)`

1つの型についてプロトコルの行を置換します。`extend-protocol` が格納するのと
同じ行を、プロトコル名の下にまとめます（各グループはそのプロトコルのその型の行を
置換します。オラクル同様）。プロトコル名なしのメソッド群は拒否され
ます（照合すべきインターフェースがありません）。インラインの
`defrecord`・`deftype` 本体と違い、メソッドに型のフィールドはローカルとして見え
ません。複数のアリティを持つメソッドはアリティを `fn` の節として書き、同じメソッドを
2度書くとオラクル同様に後の実装が残ります。

```clojure
(defprotocol P (m [x]))
(extend-type String P (m [s] :str))
(println (m "s")) ; :str
```
