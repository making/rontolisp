# clojure.set/rename-keys

`(clojure.set/rename-keys m kmap)`

Answers `m` with each key of `kmap` it holds renamed to that key's value in `kmap`. Every key of `kmap` is dropped first, so two keys may swap. `nil` answers `nil`; a record stays one unless a declared field is renamed away. Reached also as `alias/var` or a referred bare `rename-keys`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/rename-keys {:a 1 :b 2} {:a :b})) ; {:b 1}
(println (= (set/rename-keys {:a 1 :b 2} {:a :b :b :a}) {:a 2 :b 1})) ; true
```
