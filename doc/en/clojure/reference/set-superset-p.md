# clojure.set/superset?

`(clojure.set/superset? s1 s2)`

Answers whether every member of `s2` is in `s1`. Reached also as `alias/var` or a referred bare `superset?`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/superset? #{1 2} #{1})) ; true
```
