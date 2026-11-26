# clojure.set/union

`(clojure.set/union)`
`(clojure.set/union s1 s2 ...)`

すべての集合のメンバーを返します。引数なしなら `#{}`、1 つならその集合自身、それ以外は最も大きい入力へ他の入力のメンバーを conj したものです（本家と同じで、入力がすべて空なら `nil` の入力は `nil` を返します）。`alias/var` や refer された裸の `union` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/union #{1} #{1})) ; #{1}
(println (count (set/union #{1 2} #{2 3} #{4}))) ; 4
(println (set/union nil nil)) ; nil
```
