# complement

`(complement f)`

述語を否定したものを返し、他の真偽値ビルトイン同様 `T` か `false` で答えます。値としては
同じ否定の1引数ラムダです。

```clojure
(println ((complement odd?) 4)) ; true
(println (map (complement odd?) [1 2])) ; (false true)
```
