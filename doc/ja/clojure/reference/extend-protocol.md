# extend-protocol

`(extend-protocol Protocol Type (method [target & args] body...) ...)`

型を再定義せずにプロトコルの表へ行を足します。型・メソッドごとに1つの格納ラムダ
（`defmethod` 行同様。繰り返した型は後の行が勝ちます。オラクル同様）。対象は
`class` が答える種類（`String`、`Number`、`Boolean`、`Keyword`、`Symbol`、
`Character`、`Map`、`Vector`、`Set`、`List`/`Seq`、それに外れ既定としての `nil`
と `Object`。`java.lang.String`、`clojure.lang.IPersistentMap` のようなパッケージ修飾の綴りも可）、既知の record/deftype 名、それに値がインスタンスでありうる他のクラスです。
他のクラスとは、throwable（`Throwable`、`Exception`、`ExceptionInfo` など）、
`clojure.lang.IRef` や `clojure.lang.IDeref` のようなインタフェース、`java.util.Date`
（`java.sql.Timestamp` もここに届きます）、インタプリタと JVM ではホストのクラスです。
自分のクラスの行がない値は、オラクル同様、プロトコルを extend したそれらのクラスを
スーパークラス、インタフェースの順に（どちらも自分のスーパータイプより先に）試し、最後に
`Object` を試します。インタフェースへの拡張には、そのインタフェースを実装するすべての値が
届きます。レコードならマップのインタフェース（`clojure.lang.IPersistentMap`、`java.util.Map`
など）、`reify`・`deftype`・`defrecord` なら本体が挙げたインタフェース
（[reify](reify.md#host-interfaces)）、ベクタなら `clojure.lang.Sequential`、キーワードなら
`clojure.lang.IFn` です。どのクラスでもない名前は `instance?` 同様に拒否されます。複数の
アリティを持つメソッドは、アリティを `fn` の節として
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

(defprotocol R (r [x]))
(extend-protocol R
  Throwable (r [_] :throwable)
  Exception (r [_] :exception)
  clojure.lang.IRef (r [_] :ref))
(println (r (ex-info "m" {})) (r (Error. "e")) (r (atom 1))) ; :exception :throwable :ref

(defprotocol S (s [x]))
(extend-protocol S
  clojure.lang.Sequential (s [_] :sequential)
  clojure.lang.IPersistentMap (s [_] :map)
  Object (s [_] :other))
(defrecord Point [x y])
(deftype Line [] clojure.lang.Sequential)
(println (s [1]) (s '(1)) (s (Line.)) (s (->Point 1 2)) (s :k)) ; :sequential :sequential :sequential :map :other
```
