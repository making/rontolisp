# re-find

`(re-find m)`
`(re-find pattern s)`

最初のマッチを返します。マッチャー `m` のもの（進めます。オラクル通り）、または `s` 中の `pattern` のものです。グループなしではマッチ文字列、グループありでは全体と各グループのベクター（マッチしなかったグループは `nil`）です。マッチなしは `nil` です。関数値としても動きます。

```clojure
(println (re-find #"a+" "aaab")) ; aaa
(println (re-find #"a+" "c")) ; nil
(println (re-find #"(\w+)@(\w+)" "user@host")) ; [user@host user host]
```
