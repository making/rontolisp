# defprotocol

`(defprotocol Name docstring? (method [target & args]+ docstring?) ...)`

プロトコルを宣言します。メソッドごとにターゲットのタグ上のディスパッチャ（階層
探索なしの multimethod 形）を持ちます。名前はメソッド表を答えます。呼び出しは
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
