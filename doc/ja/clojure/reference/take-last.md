# take-last

`(take-last n coll)`

`coll` の seq の最後の `n` 個を strict なリストで返します。`n` が正でないか `coll` が
空なら `nil` です。オラクル同様に入力全体を realize します。値としては2引数の関数です。

```clojure
(println (take-last 2 [1 2 3])) ; (2 3)
(println (take-last 0 [1 2 3])) ; nil
```
