# namespace-munge

`(namespace-munge ns)`

`ns` の `str` のダッシュをすべてアンダースコアにしたもの。oracle と同じ。値としては 1 引数を取る。

```clojure
(println (namespace-munge "my.ns-name")) ; my.ns_name
```
