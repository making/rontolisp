# clojure.set/project

`(clojure.set/project xrel ks)`

Answers the set of the relation's members narrowed to the keys `ks`, like `select-keys`; equal narrowings merge. Reached also as `alias/var` or a referred bare `project`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/project #{{:a 1 :b 2} {:a 1 :b 3}} [:a])) ; #{{:a 1}}
```
