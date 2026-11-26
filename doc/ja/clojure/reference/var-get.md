# var-get

`(var-get v)`

`clojure.core/var-get` です。var `v` のルート値を返します。var に対する `deref` と同じ答えで、
有効な `binding` も反映します。`deref` と違い、var でない値はアトムでもシグナルを上げます。
値としては1引数の関数です。

```clojure
(def x 5)
(println (var-get #'x))            ; 5
(def ^:dynamic *d* 1)
(println (binding [*d* 2] (var-get #'*d*))) ; 2
(println (map var-get [#'x #'*d*])) ; (5 1)
```
