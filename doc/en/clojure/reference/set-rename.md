# clojure.set/rename

`(clojure.set/rename xrel kmap)`

Answers the set of the relation's members with their keys renamed by `kmap`, as `rename-keys` does. Reached also as `alias/var` or a referred bare `rename`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/rename #{{:a 1}} {:a :z})) ; #{{:z 1}}
```
