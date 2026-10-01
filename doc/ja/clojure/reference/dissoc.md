# dissoc

`(dissoc m k ...)`

`m` のペアから与えたキーを引いた新しいマップを返します。存在しないキーは無視され、`m` は決して書き換えられません。`nil` の `dissoc` は `nil` です。record に対しては宣言フィールドが全部残る間は型が保たれ、そうでなければオラクル同様プレーンなマップに落ちます。

値としてはマップに残り引数のキー列を取ります。

仕様との差異: transient（`dissoc!`）は名前で拒否されます。マップでないものへの誤用は、オラクルの代わりに Common Lisp の型エラーを上げることがあります。

```clojure
(println (get (dissoc {:a 1 :b 2} :a) :a)) ; nil
(println (count (dissoc {:a 1 :b 2} :a :b))) ; 0
(println (dissoc nil :a))                  ; nil
(println (map dissoc [{:a 1 :b 2}] [:a]))  ; ({:b 2})
```
