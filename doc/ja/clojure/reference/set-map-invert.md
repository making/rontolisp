# clojure.set/map-invert

`(clojure.set/map-invert m)`

`m` の各値からそのキーへのマップを返します。値を共有する 2 つのキーは一方だけが残ります。`nil` には `{}` を返します。`alias/var` や refer された裸の `map-invert` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/map-invert {:a 1})) ; {1 :a}
```
