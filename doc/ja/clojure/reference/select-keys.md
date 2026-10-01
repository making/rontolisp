# select-keys

`(select-keys m keys)`

存在するキーだけを持つ新しいマップを返します。`nil` では空マップ、それ以外の非マップでは
シグナルします。値としては2引数のラムダです。

```clojure
(println (select-keys {:a 1 :b 2} [:a :c])) ; {:a 1}
```
