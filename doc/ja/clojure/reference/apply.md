# apply

`(apply f x... args)`

先頭の引数を並べ、その後ろに最後の引数の seq ビューを展開して `f` を呼びます。オラクルや CL の `apply` と同じです。値として動作するため、`reduce` と組み合わせられます。

```clojure
(println (apply max '(3 9 4)))  ; 9
(println (apply max [3 9 4]))   ; 9
(println (apply + 1 2 [3 4]))   ; 10
(println (apply concat [[1 2] [3]])) ; (1 2 3)
```
