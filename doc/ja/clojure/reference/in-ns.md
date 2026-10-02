# in-ns

`(in-ns 'name)`

現在の namespace を切り替え（なければ作り）、`nil` を返します。以降の定義はその namespace に属し、名前もそこで解決されます。`ns` と違って配線もロードもせず、core も見えたままです（oracle では `in-ns` で新しく作った namespace から core は見えません）。

```clojure
(println (in-ns 'demo)) ; nil
```
