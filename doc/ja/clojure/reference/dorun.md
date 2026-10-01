# dorun

`(dorun coll)` / `(dorun n coll)`

効果のためにコレクションを実現し `nil` を答えます。seq は既に strict なリストなので、
実現とは評価することです。省略可能なカウントは評価されるだけです。関数値にもなります。

```clojure
(println (dorun [1 2 3])) ; nil
```
