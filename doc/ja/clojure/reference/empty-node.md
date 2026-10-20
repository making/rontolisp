# EMPTY-NODE

`EMPTY-NODE`

oracle の persistent-vector ノードで、ここでは空のベクターです：`=`、`count`、`seq`、
印字、すべての動詞は `[]` として扱い、2 つの参照は同一オブジェクトです（`identical?`）。
呼び出しは `[]` の呼び出しと同様で、`nth` によるキーと省略可能な既定値、それ以外の個数は
ロード時に拒否されます（[Deviations](../deviations.md) を見てください）。

```clojure
(prn EMPTY-NODE (= EMPTY-NODE [])) ; [] true
(prn (EMPTY-NODE 0 :dflt)) ; :dflt
```
