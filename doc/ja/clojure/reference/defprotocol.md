# defprotocol

`(defprotocol Name docstring? (method [target & args] docstring?) ...)`

プロトコルを宣言します。メソッドごとにターゲットのタグ上のディスパッチャ（階層
探索なしの multimethod 形）を持ちます。名前はメソッド表を答えます。呼び出しは
ターゲットのタグの完全一致、それから `Object` 行でディスパッチし、`Object` 行
なしの外れはオラクル同様シグナルを上げます。各メソッドは1つのパラメータベクター
を取ります（複数アリティは拒否のまま、`:extend-via-metadata` も同様）。

```clojure
(defprotocol P (greet [x]))
(defrecord R [name] P (greet [_] name))
(extend-protocol P nil (greet [_] :nobody))
(println (greet (->R "Ada"))) ; Ada
(println (greet nil))         ; :nobody
```
