# compare-and-set!

`(compare-and-set! atom expected v)`

アトムの値が `expected` と等しければ `v` を格納します。いずれの場合も `true` か `false` を返し、新しい値は `deref` で読み返せます。比較は `eql` です。数は値で、それ以外は同一性で比較します。

```clojure
(def a (atom 2))
(println (compare-and-set! a 2 3)) ; true
(println (compare-and-set! a 2 9)) ; false
(println @a) ; 3
```
