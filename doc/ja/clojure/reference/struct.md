# struct

`(struct struct-map value...)`

struct のキーと値を組み合わせた新しいマップです。足りない値は `nil`、多すぎる値はシグナルを上げます。以後は普通のマップです。キーワードで読み、動詞で作り替えます。

```clojure
(defstruct point3 :x :y :z)
(println (:z (struct point3 1 2))) ; nil
(println (:y (struct point3 1 2 3))) ; 2
```
