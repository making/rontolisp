# clojure.set/intersection

`(clojure.set/intersection s1 s2 ...)`

Answers the members every set holds, shrinking the smallest input; of one set, the set itself. Reached also as `alias/var` or a referred bare `intersection`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/intersection #{1 2 3} #{2 3} #{3 4})) ; #{3}
(println (set/intersection #{1} nil)) ; nil
```
