# doall

`(doall coll)` / `(doall n coll)`

`dorun` と同様ですが、コレクション自身を答えます -- 強制はされないので、ベクターは
ベクターのままです。関数値にもなります。

```clojure
(println (doall [1 2 3])) ; [1 2 3]
```
