# var?

`(var? x)`

`clojure.core/var?`: var（`#'x`）なら `true` を返します。シンボルや var の値は `false` です。値としては1引数の関数です。

```clojure
(def vp-x 1)
(println (var? #'vp-x) (var? 'vp-x) (var? vp-x))  ; true false false
```
