# group-by

`(group-by f coll)`

関数の値から、それに当てはまる要素のベクターへの新しいマップを返します。要素は出現順で、
空コレクションでは空マップです。値としては2引数のラムダです。

```clojure
(println (group-by odd? [1 3])) ; {true [1 3]}
(println (group-by (fn [x] :same) [1 2])) ; {:same [1 2]}
```
