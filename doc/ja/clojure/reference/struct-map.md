# struct-map

`(struct-map struct-map key value...)`

struct のキーを持つ新しいマップです（ここで上書きしない限り `nil`）。オラクルの名前付きスロットによる3引数コンストラクタと同様です。

```clojure
(defstruct account :id :balance)
(println (:balance (struct-map account :id 9))) ; nil
(println (:id (struct-map account :id 9))) ; 9
```
