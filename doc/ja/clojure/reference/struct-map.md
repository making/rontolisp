# struct-map

`(struct-map struct-map key value...)`

struct のキーを持つ新しいマップです（ここで上書きしない限り `nil`）。オラクルの名前付きスロットによる3引数コンストラクタと同様です。

```clojure
(defstruct ticket :seat :price)
(println (:price (struct-map ticket :seat "B7"))) ; nil
(println (:seat (struct-map ticket :seat "B7"))) ; B7
```
