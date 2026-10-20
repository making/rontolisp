# definline

`(definline name docstring? [params...] body...)`
`(definline name docstring? ([params...] body...)+`

[`defn`](defn.md) とまったく同じく関数を定義します。同じアリティ、同じ直接呼び出し、
同じファーストクラスの値、同じ前方参照です。oracle はさらに本体を呼び出し側向けの
inline 展開子として記録しますが、ここではその記録は文書化された no-op です -- 呼び出し側
を inline 展開するものはなにもない -- ので、定義は関数そのものです。値としては未知です
（`(def x definline)` は拒否されます）。

```clojure
(definline di-add [x y] (+ x y))
(println (di-add 2 3)) ; 5
```
