# clojure.set/difference

`(clojure.set/difference s1 s2 ...)`

Answers the members of `s1` no later set holds; of one set, the set itself. Reached also as `alias/var` or a referred bare `difference`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/difference #{1 2 3} #{1} #{2})) ; #{3}
```
