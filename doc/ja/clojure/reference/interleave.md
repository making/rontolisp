# interleave

`(interleave coll...)`

各 seq ビューから1つずつ交互に取り出した結果を返し、最短のもので止まります（オラクル同様）。
入力のどれかが lazy なら lazy seq を、そうでなければ strict なリストを返します。
`(interleave)` は `nil` です。値としては全引数を交互にします。

```clojure
(println (interleave [1 2] [:a :b])) ; (1 :a 2 :b)
(println (take 4 (interleave (iterate inc 0) (repeat :x)))) ; (0 :x 1 :x)
```
