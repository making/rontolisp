# .name / .-name

`(.method receiver args...)` `(.-field receiver)`

`.` の糖衣です。ドット付きの名前は receiver へのメソッド呼び出し、`.-` ドット付きの名前はインスタンスフィールドの読み取りです。interop のどこでもそうであるように receiver が経路を決めるため、文字列の receiver は対応する core 操作を取り、それ以外の `String` メソッドは文字列を `String` として呼びます。ホストオブジェクトでない値の `.toString` は、全バックエンドでその `str` の綴りを返します。コレクション・キーワード・シンボル・比・atom もホストオブジェクトを持たないため、`clojure.lang`/`java.util` のよく使うメソッド（`.count`・`.size`・`.isEmpty`・`.get`・`.nth`・`.valAt`・`.contains`・`.containsKey`・`.indexOf`・`.getName`・`.getNamespace`・`.numerator`・`.deref` など）は全バックエンドで対応する core 関数を通して答えます。オラクルのクラスが実装する JDK インターフェースのそれ以外のメソッド（`.toArray`・`.containsAll`・`.entrySet`・`.stream`・`.sort`）は、インタープリターと JVM では値が Java へ渡るときの読み取り専用の Java オブジェクトに対して呼び、wasm バックエンドでは名前を挙げて拒否します。そのクラスにないメソッドはオラクルと同じ文言で拒否し（`No matching field found: toArray for class clojure.lang.Keyword`）、オラクルのクラスが持ちうるがここでは答えないメソッド（`.reduce`・`.meta`）は名前を挙げて拒否します。record・deftype・reify は全バックエンドでそのクラスが持つものに答えます。本体が実装するプロトコルメソッドはそれを呼び（`(.m r)`）、宣言フィールドを名指す引数なしの名前はそのフィールドを読みます（`(.a r)`）。`extend-type` のメソッド、宣言されていない名前、可変フィールドはオラクルと同じ文言で拒否します（`No matching field found: q for class user.R`）。プログラムが定義したどの record・deftype のプロトコルメソッドでもフィールドでもない名前は、コレクションと同じように扱います（record は `java.util.Map` です）。それ以外はインタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.compareTo "a" "b")) ; -1
(println (.toString [1 "a"])) ; [1 "a"]
(println (.count [1 2 3]) (.get {:a 1} :a) (.getName :k)) ; 3 1 k
(defprotocol P (m [this]))
(defrecord R [a] P (m [this] (str "m" a)))
(println (.m (->R 1)) (.a (->R 1))) ; m1 1
```
