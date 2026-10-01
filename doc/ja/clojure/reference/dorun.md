# dorun

`(dorun coll)` / `(dorun n coll)`

効果のためにコレクションを実現し `nil` を答えます。strict な seq は既にリストなので、
実現とは評価することです。lazy seq は評価されるだけで強制されません -- 先に take してください。
省略可能なカウントは評価されるだけです。関数値にもなります。

```clojure
(println (dorun [1 2 3])) ; nil
```
