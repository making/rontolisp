# clojure.set/select

`(clojure.set/select pred xset)`

Answers the members of `xset` for which `pred` is truthy; `nil` answers `nil`. Reached also as `alias/var` or a referred bare `select`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/select odd? #{1 2})) ; #{1}
(println (set/select :a #{{:a 1} {:b 2}})) ; #{{:a 1}}
```
