# not-empty

`(not-empty coll)`

`coll` の seq に要素があれば `coll` そのものを、なければ `nil` を返します。seq を持たない
値（数値など）はオラクル同様にシグナルします。値としては1引数の関数です。

```clojure
(prn (not-empty [1])) ; [1]
(prn (not-empty "")) ; nil
```
