# defprotocol

`(defprotocol Name docstring? (method [target & args]+ docstring?)*)`

プロトコルを宣言します。メソッドごとにターゲットのタグ上のディスパッチャ（階層
探索なしの multimethod 形）を持ちます。名前はプロトコルのマップをオラクルと同じ形で
答えます。`:on`/`:on-interface` にインタフェース（ここではクラスキーワード）、`:sigs` に
各メソッドのパラメータベクター、メソッドマップと拡張表を `:impls` に持ち、`extend`・
`satisfies?`・`extends?`・`extenders` は呼び出し位置の名前と同様にこれを受け取ります。
`:var` と `:method-builders` は持ちません。インタフェースのドット付きの名前（`user.P`、
`my_app.core.P`）はそのクラス値です。名前として印字し、`class?` に答え、それへの
`instance?`、`isa?`、`class` でディスパッチするマルチメソッドは、本体でプロトコルを実装する
record・deftype・`reify` をオラクルと同じく受け取ります（拡張では実装になりません）。その
[print-method](print-method.md) も同様です。`:on-interface` のキーワードとは `=` になりません。
呼び出しは
ターゲットのタグの完全一致、次にターゲットが継承または実装するクラスのうちプロトコルを
extend したもの（throwable、`IRef` など。[extend-protocol](extend-protocol.md)）、
それから `Object` 行でディスパッチし、`Object` 行なしの外れはオラクル同様シグナルを
上げます。メソッドはアリティごとに1つのパラメータ
ベクターを宣言し、どのベクターも先頭でターゲットを受け取ります。呼び出しは、実装の
アリティのうち引数の数が一致するものに届きます。`defrecord`・`deftype`・`reify` の本体は
メソッド名を別のパラメータベクターで書き直して別のアリティを実装し、どのアリティも
プロトコルが宣言したものでなければなりません。[extend-protocol](extend-protocol.md)、
[extend-type](extend-type.md)、[extend](extend.md) はアリティを `fn` の節として書きます。
実装が受け取らない引数の数の呼び出しは `ArityException` をシグナルします（インライン本体が
実装していないアリティについて、オラクルは `AbstractMethodError` を投げます）。

メソッドを1つも宣言しないプロトコル（マーカープロトコル）も書けます。`extend-type`・
`extend-protocol`・`extend`・`defrecord`・`deftype`・`reify` は実装なしでそれを名指しでき、
`satisfies?`/`extends?` は他のプロトコルと同じように答えます。

`:extend-via-metadata true` のプロトコルでは、値がメタデータ（[with-meta](with-meta.md)）
でもメソッドを実装できます。キーは名前空間で修飾したメソッドのシンボル（`` `area `` または
`'user/area`）です。順序はオラクルと同じで、`defrecord`/`deftype`/`reify` 本体の実装、
メタデータ、`extend-type` の行と `Object` の順です。`satisfies?` はオラクル同様メタデータを
見ません。

```clojure
(defprotocol P (greet [x]))
(defrecord R [name] P (greet [_] name))
(extend-protocol P nil (greet [_] :nobody))
(println (greet (->R "Ada"))) ; Ada
(println (greet nil))         ; :nobody

(defprotocol Area :extend-via-metadata true (area [s]))
(println (area (with-meta {:w 2 :h 3} {`area (fn [s] (* (:w s) (:h s)))}))) ; 6

(defprotocol Scaled (scale [s] [s k]))
(defrecord Square [side] Scaled (scale [_] (* side side)) (scale [this k] (* k (scale this))))
(extend-protocol Scaled String (scale ([s] (count s)) ([s k] (* k (count s)))))
(println (scale (->Square 3)) (scale (->Square 3) 2)) ; 9 18
(println (scale "abc") (scale "abc" 2))               ; 3 6
```
