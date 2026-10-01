# re-seq

`(re-seq pattern s)`

`s` 中の `pattern` のすべてのマッチを seq で返します（オラクルの lazy も同じ印字です）。各要素は `re-find` 形なので、グループはベクターに広がります。マッチなしは `nil` です。関数値としても動きます。

```clojure
(println (re-seq #"a+" "aaabbaa")) ; (aaa aa)
(println (re-seq #"z" "abc")) ; nil
```
