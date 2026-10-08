# extend-protocol

`(extend-protocol Protocol Type (method [target & args] body...) ...)`

型を再定義せずにプロトコルの表へ行を足します。型・メソッドごとに1つの格納ラムダ
（`defmethod` 行同様。繰り返した型は後の行が勝ちます。オラクル同様）。対象は
`class` が答える種類（`String`、`Number`、`Boolean`、`Keyword`、`Symbol`、
`Character`、`Map`、`Vector`、`Set`、`List`/`Seq`、それに外れ既定としての `nil`
と `Object`。`java.lang.String`、`clojure.lang.IPersistentMap` のようなパッケージ修飾の綴りも可）と既知の record/deftype 名です。それ以外（`Instant`、`Date` 等）は
名前付きで拒否されます。複数のアリティを持つメソッドは、アリティを `fn` の節として
`(method ([target] ...) ([target x] ...))` のように書きます。

```clojure
(defprotocol P (m [x]))
(extend-protocol P
  nil (m [_] :nil)
  String (m [s] :str)
  Object (m [_] :other))
(println (m nil))  ; :nil
(println (m "s"))  ; :str
(println (m 1.5))  ; :other

(defprotocol Q (q [x] [x y]))
(extend-protocol Q Long (q ([n] n) ([n k] (* n k))))
(println (q 7) (q 7 6)) ; 7 42
```
