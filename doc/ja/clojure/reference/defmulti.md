# defmulti

`(defmulti name docstring? dispatch-fn :default default?)`

multimethod を定義します。ディスパッチ値をキーとするメソッド表と、ディスパッチャの組です。`dispatch-fn` は呼び出しごとに走り、その結果がメソッドを選びます。省略可能な `:default` はフォールバックのディスパッチ値の名前です（オプションがなければ `:default` 自身）。`:hierarchy` はディスパッチ検索がグローバルの代わりに歩く階層値を取ります。呼び出しのたびに評価されます。名前は、各呼び出しのディスパッチ値を表へ適用する rest 引数の関数へ低レベル化されます。

デフォルトメソッドなしのミスは `No method in <name> for dispatch value: <value>` をシグナルします。ディスパッチ検索は完全一致の先を階層を通って広げます（`defmethod` を見てください）。

`class` ディスパッチは `class` が答える種類キーワード（`:string`、`:number`、`:map`、…）
の上で走るので、ホストの綴りが同じ行を指します。`String`、`Number`（すべての数値綴りは
`:number` にまとまります。オラクルは `Long` と `Double` を区別します）、`java.util.Map`
のようなドット名、`clojure.lang.IPersistentVector`、`nil` です。`Object` メソッドは階層
検索を逃したものを、デフォルトの先に捕まえます。

```clojure
(defmulti area :shape)
(defmethod area :default [m] 0)
(println (area {:shape :x})) ; 0

(defmulti what "tags" (fn [x] (:t x)) :default :other)
(defmethod what :other [x] 99)
(println (what {:t :zzz})) ; 99

(defmulti printable class)
(defmethod printable String [s] (str "str:" s))
(defmethod printable nil [_] "was-nil")
(defmethod printable :default [x] "dflt")
(println (printable "a")) ; str:a
(println (printable nil)) ; was-nil
(println (printable :k)) ; dflt
```
