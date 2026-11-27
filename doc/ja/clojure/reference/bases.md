# bases

`(bases c)`

クラス `c` の直接のスーパークラスと直接のインタフェースを、スーパークラスを先頭にクラス値の
シーケンスで返します。どちらもないとき（`Object`、何も継承しないインタフェース）と nil には nil を
返します。それ以外の引数はオラクルと同じ `ClassCastException` です。値としては1引数ラムダです。

すべてのバックエンドが答えます。ホストのクラス（インタプリタ、Java 相互運用を使う JVM プログラム）は
ホストのリフレクションで、それ以外はオラクルから読み取った表で答えます。表は、値またはそのスーパー
タイプが持ちうる `clojure.lang` のクラスと JDK のクラスのものです。関数のクラスは
`clojure.lang.AFunction` を、record は `Object`、本体が挙げるインタフェース（プロトコルのものを含む）、
続いて `IRecord`・`IHashEq`・`IObj`・`ILookup`・`IKeywordLookup`・`IPersistentMap`・`java.util.Map`・
`Serializable` を、deftype は `Object`、本体のインタフェース、続いて `IType` を返します。

`reify` のクラスは本体のインタフェースと `clojure.lang.IObj` を、`definterface` の名前は nil を、
ホストがあるところでは返します。ホストがないところ（wasm バックエンド）ではこの2つは拒否します。

逸脱: 答えはリスト（`PersistentList`。オラクルは `Cons`）です。record と deftype の本体の
インタフェースは本体が挙げた順で、オラクルは複数あるとハッシュセットの順にします。ここでどの値も
持たない JDK のクラス（wasm での `java.util.HashMap` のクラスリテラル）は拒否します。

```clojure
(println (bases Long)) ; (java.lang.Number java.lang.Comparable java.lang.constant.Constable java.lang.constant.ConstantDesc)
(println (bases (class []))) ; (clojure.lang.APersistentVector clojure.lang.IObj clojure.lang.IEditableCollection clojure.lang.IReduce clojure.lang.IKVReduce clojure.lang.IDrop)
(println (bases Object) (bases nil)) ; nil nil
```
