# clojure.set/union

`(clojure.set/union)`
`(clojure.set/union s1 s2 ...)`

Answers the members of every set: of none `#{}`, of one the set itself, otherwise the others' members conjoined onto the largest input, like the oracle (so `nil` inputs answer `nil` when every input is empty). Reached also as `alias/var` or a referred bare `union`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/union #{1} #{1})) ; #{1}
(println (count (set/union #{1 2} #{2 3} #{4}))) ; 4
(println (set/union nil nil)) ; nil
```
