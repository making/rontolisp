# apply

`(apply f x... args)`

先頭の引数を並べ、その後ろに最後の引数の seq ビューを展開して `f` を呼びます。オラクルや CL の `apply` と同じです。値として動作するため、`reduce` や `partial` と組み合わせられます。その場合も最後の引数はどの seqable でも構いません。

```clojure
(println (apply max '(3 9 4)))  ; 9
(println (apply max [3 9 4]))   ; 9
(println (apply + 1 2 [3 4]))   ; 10
(println (apply concat [[1 2] [3]])) ; (1 2 3)
(println (reduce (partial apply assoc-in) {} [[[:a] 1] [[:b :c] 2]])) ; {:a 1, :b {:c 2}}
```
