# clojure.set/index

`(clojure.set/index xrel ks)`

Answers a map from each distinct narrowing of the members to the keys `ks` to the set of members narrowing to it. Reached also as `alias/var` or a referred bare `index`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(def idx (set/index #{{:a 1 :b 2} {:a 1 :b 3} {:a 2 :b 2}} [:a]))
(println (get idx {:a 2})) ; #{{:a 2, :b 2}}
(println (count (get idx {:a 1}))) ; 2
```
