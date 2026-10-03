# clojure.set/subset?

`(clojure.set/subset? s1 s2)`

Answers whether every member of `s1` is in `s2`. Reached also as `alias/var` or a referred bare `subset?`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/subset? #{1} #{1 2})) ; true
(println (set/subset? #{3} #{1 2})) ; false
```
