# distinct?

`(distinct? x & more)`

`clojure.core/distinct?`: どの2つの引数も `=` でなければ `true` を返します。コレクションは内容で比較します。値としては1個以上の引数をとる関数です。

```clojure
(println (distinct? 1 2 3) (distinct? 1 2 1) (distinct? [1] '(1)))  ; true false false
```
