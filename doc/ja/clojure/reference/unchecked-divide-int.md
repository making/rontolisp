# unchecked-divide-int

`(unchecked-divide-int a b)`

引数を `unchecked-add-int` と同様にintに変換し、ゼロ方向へ切り捨てた商を返します。`-2147483648` を `-1` で割ったときだけ `-2147483648` に折り返します。除数が0のときはシグナルします。値としては2引数の関数です。

```clojure
(println (unchecked-divide-int -7 2)) ; -3
```
