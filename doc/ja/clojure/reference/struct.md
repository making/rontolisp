# struct

`(struct struct-map value...)`

struct のキーと値を組み合わせた新しいマップです。足りない値は `nil`、多すぎる値はシグナルを上げます。以後は普通のマップです。キーワードで読み、動詞で作り替えます。

```clojure
(defstruct account :id :balance)
(println (:balance (struct account 1))) ; nil
(println (:id (struct account 1 100))) ; 1
```
