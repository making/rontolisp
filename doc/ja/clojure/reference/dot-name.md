# .name / .-name

`(.method receiver args...)` `(.-field receiver)`

`.` の糖衣です。ドット付きの名前は receiver へのメソッド呼び出し、`.-` ドット付きの名前はインスタンスフィールドの読み取りです。interop のどこでもそうであるように receiver が経路を決めるため、文字列の receiver は対応する core 操作を取ります。ホストオブジェクトでない値の `.toString` は、全バックエンドでその `str` の綴りを返します。それ以外はインタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.toString [1 "a"])) ; [1 "a"]
```
