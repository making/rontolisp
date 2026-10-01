# reify

`(reify Protocol (method [target & args] body...) ...)`

評価ごとに新しいディスパッチ値を答えます。各プロトコルの表に行を持ちます。
使い切りのマップにメソッドを添えたものであり、`proxy` ではありません（`proxy`
は `java:` サーフェスのまま）。各インスタンスは独自のタグでディスパッチする
ため、2つのインスタンスが `=` になることは決してありません（オラクル同様）。
それ以外の `=` は同一性です。メソッド群は `extend-type` 同様プロトコル名の下に
置きます。

```clojure
(defprotocol P (m [x]))
(def a (reify P (m [_] :a)))
(def b (reify P (m [_] :b)))
(println (m a))       ; :a
(println (m b))       ; :b
(println (= a a))     ; true
(println (= a b))     ; false
(println (satisfies? P a)) ; true
```
