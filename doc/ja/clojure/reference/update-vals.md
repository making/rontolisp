# update-vals

`(update-vals m f)`

`m` の全ての値を `(f value)` にしたものを返します。マップとレコードは新しいマップに、
`nil` は空マップになり、ベクターはオラクル同様ベクターのままです。値としては2引数の関数です。

```clojure
(prn (update-vals {:a 1} inc)) ; {:a 2}
(prn (update-vals [1 2] inc)) ; [2 3]
```
