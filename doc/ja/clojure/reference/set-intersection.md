# clojure.set/intersection

`(clojure.set/intersection s1 s2 ...)`

すべての集合が持つメンバーを返します。最も小さい入力を縮めて求めます。集合 1 つならその集合自身です。`alias/var` や refer された裸の `intersection` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/intersection #{1 2 3} #{2 3} #{3 4})) ; #{3}
(println (set/intersection #{1} nil)) ; nil
```
