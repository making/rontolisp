# clojure.set/select

`(clojure.set/select pred xset)`

`pred` が真になる `xset` のメンバーを返します。`nil` には `nil` を返します。`alias/var` や refer された裸の `select` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/select odd? #{1 2})) ; #{1}
(println (set/select :a #{{:a 1} {:b 2}})) ; #{{:a 1}}
```
