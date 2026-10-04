# .name / .-name

`(.method receiver args...)` `(.-field receiver)`

`.` の糖衣です。ドット付きの名前は receiver へのメソッド呼び出し、`.-` ドット付きの名前はインスタンスフィールドの読み取りです。interop のどこでもそうであるように receiver が経路を決めるため、文字列の receiver は対応する core 操作を取り、それ以外の `String` メソッドは文字列を `String` として呼びます。ホストオブジェクトでない値の `.toString` は、全バックエンドでその `str` の綴りを返します。コレクション・キーワード・シンボル・比・atom もホストオブジェクトを持たないため、`clojure.lang`/`java.util` のよく使うメソッド（`.count`・`.size`・`.isEmpty`・`.get`・`.nth`・`.valAt`・`.contains`・`.containsKey`・`.indexOf`・`.getName`・`.getNamespace`・`.numerator`・`.deref` など）は全バックエンドで対応する core 関数を通して答え、そのクラスにないメソッドはオラクルと同じ文言で、それ以外のメソッドは名前を挙げて拒否します。それ以外はインタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.compareTo "a" "b")) ; -1
(println (.toString [1 "a"])) ; [1 "a"]
(println (.count [1 2 3]) (.get {:a 1} :a) (.getName :k)) ; 3 1 k
```
