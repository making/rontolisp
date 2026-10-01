# in-ns

`(in-ns 'name)`

現在の namespace を切り替え、`nil` を返します。namespace はフラットで、alias と refer の配線のほかに namespace ごとの定義や参照はないため、切り替えは帳簿上のものです。

```clojure
(println (in-ns 'demo)) ; nil
```
