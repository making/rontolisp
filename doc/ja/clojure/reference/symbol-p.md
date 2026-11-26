# symbol?

`(symbol? x)`

識別子で `true` です。キーワード（リストです）・真偽値・`nil` はオラクル同様シンボルでは
ありません。値としては `T`-or-`false` で答える1引数ラムダです。

```clojure
(println (symbol? 'a) (symbol? :a)) ; true false
```
