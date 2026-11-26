# string?

`(string? x)`

文字列で `true` です。値としては `T`-or-`false` で答える1引数ラムダです。

```clojure
(println (string? "a") (string? 1)) ; true false
```
