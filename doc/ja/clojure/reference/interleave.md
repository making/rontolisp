# interleave

`(interleave coll...)`

各 seq ビューから1つずつ交互に取り出した結果を返し、最短のもので止まります（オラクル同様）。
`(interleave)` は `nil` です。値としては全引数の seq ビューを交互にします。

```clojure
(println (interleave [1 2] [:a :b])) ; (1 :a 2 :b)
```
