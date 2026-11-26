# clojure.set/subset?

`(clojure.set/subset? s1 s2)`

`s1` のすべてのメンバーが `s2` にあるかどうかを返します。`alias/var` や refer された裸の `subset?` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/subset? #{1} #{1 2})) ; true
(println (set/subset? #{3} #{1 2})) ; false
```
