# clojure.set/project

`(clojure.set/project xrel ks)`

関係の各メンバーを `select-keys` と同じくキー `ks` に絞った集合を返します。等しくなったものは 1 つにまとまります。`alias/var` や refer された裸の `project` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/project #{{:a 1 :b 2} {:a 1 :b 3}} [:a])) ; #{{:a 1}}
```
