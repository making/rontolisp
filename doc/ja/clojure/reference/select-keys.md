# select-keys

`(select-keys m keys)`

存在するキーだけを持つ新しいマップを返します。`nil` では空マップ、Java の `Map` は `find` と
同じく各キーをそれ自身で探します（インタプリタと JVM）。それ以外の非マップではシグナルします。
値としては2引数のラムダです。

```clojure
(println (select-keys {:a 1 :b 2} [:a :c])) ; {:a 1}
```
