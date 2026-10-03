# clojure.set/map-invert

`(clojure.set/map-invert m)`

Answers a map from each value of `m` to its key; of two keys sharing a value, one survives. Of `nil`, `{}`. Reached also as `alias/var` or a referred bare `map-invert`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/map-invert {:a 1})) ; {1 :a}
```
