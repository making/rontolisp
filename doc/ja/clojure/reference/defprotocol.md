# defprotocol

`(defprotocol Name docstring? (method [target & args] docstring?) ...)`

プロトコルを宣言します。メソッドごとにターゲットのタグ上のディスパッチャ（階層
探索なしの multimethod 形）を持ちます。名前はメソッド表を答えます。呼び出しは
ターゲットのタグの完全一致、それから `Object` 行でディスパッチし、`Object` 行
なしの外れはオラクル同様シグナルを上げます。各メソッドは1つのパラメータベクター
を取ります（複数アリティは拒否のまま）。

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
```
