# clojure.set/superset?

`(clojure.set/superset? s1 s2)`

`s2` のすべてのメンバーが `s1` にあるかどうかを返します。`alias/var` や refer された裸の `superset?` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/superset? #{1 2} #{1})) ; true
```
