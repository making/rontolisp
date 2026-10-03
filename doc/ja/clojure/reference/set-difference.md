# clojure.set/difference

`(clojure.set/difference s1 s2 ...)`

後続のどの集合も持たない `s1` のメンバーを返します。集合 1 つならその集合自身です。`alias/var` や refer された裸の `difference` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/difference #{1 2 3} #{1} #{2})) ; #{3}
```
