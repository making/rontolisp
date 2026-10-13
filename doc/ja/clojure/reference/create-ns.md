# create-ns

`(create-ns sym)`

シンボル `sym` の名前空間。なければ作る（消したものは作り直す）。oracle と同じ。シンボル以外は拒否される。値としては 1 引数を取る。

```clojure
(println (ns-name (create-ns 'doc-eg-ns))) ; doc-eg-ns
```
