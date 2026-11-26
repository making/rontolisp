# doall

`(doall coll)` / `(doall n coll)`

`dorun` と同様ですが、コレクション自身を答えます -- 変換はされないので、ベクターは
ベクターのままで、realize された lazy seq はその要素を表示します。関数値にもなります。

```clojure
(println (doall [1 2 3])) ; [1 2 3]
```
