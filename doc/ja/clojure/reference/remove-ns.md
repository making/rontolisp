# remove-ns

`(remove-ns sym)`

シンボル `sym` の名前空間を表から外す。以後の `find-ns` は見失い、`all-ns` は飛ばす。作ったことのないものは `nil`。oracle と同じ。シンボル以外は拒否される。値としては 1 引数を取る。

```clojure
(create-ns 'doc-eg-gone)
(remove-ns 'doc-eg-gone)
(println (some? (find-ns 'doc-eg-gone))) ; false
```
