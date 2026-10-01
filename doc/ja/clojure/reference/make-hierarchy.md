# make-hierarchy

`(make-hierarchy)`

空の階層値を返します。`:parents`/`:ancestors`/`:descendants` の表からなるマップです。`derive`/`underive` の 3 引数形式、`isa?`/`parents`/`ancestors`/`descendants` の追加引数形式、または `defmulti` の `:hierarchy` へ与えます。

```clojure
(def h (make-hierarchy))
(println (isa? h :c :p)) ; false
(println (isa? (derive h :c :p) :c :p)) ; true
```
