# flush

`(flush)`

`*out*` をフラッシュして `nil` を答えます。`print` で書いたプロンプトは、入力を待つ前にこれが必要です。値としては引数なしの関数です。

```clojure
(print "name: ")
(prn (flush)) ; nil
```
