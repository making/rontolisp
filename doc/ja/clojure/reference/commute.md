# commute

`(commute r f args...)`

`alter` と同じですが、オラクルでは競合時に `f` が2回走りうる可換な答えを取ります。ここでは競合が起きないため関数はちょうど1回走り、両動詞は区別できません。`alter` 同様 `dosync` が必要です。

```clojure
(def r (ref 1))
(dosync (commute r * 6))
(println @r) ; 6
```
