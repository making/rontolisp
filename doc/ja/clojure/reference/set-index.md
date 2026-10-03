# clojure.set/index

`(clojure.set/index xrel ks)`

メンバーをキー `ks` に絞った結果ごとに、そう絞られるメンバーの集合を対応させたマップを返します。`alias/var` や refer された裸の `index` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(def idx (set/index #{{:a 1 :b 2} {:a 1 :b 3} {:a 2 :b 2}} [:a]))
(println (get idx {:a 2})) ; #{{:a 2, :b 2}}
(println (count (get idx {:a 1}))) ; 2
```
