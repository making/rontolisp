# in-ns

`(in-ns 'name)`

現在の namespace を切り替え（なければ作り）、`nil` を返します。以降の定義はその namespace に属し、名前もそこで解決され、実行された時点から `*ns*` はその namespace です。`ns` と違って配線もロードもせず、core も見えたままです（oracle では `in-ns` で新しく作った namespace から core は見えません）。値としては引数 1 つをとり、実行時に拒否します。namespace の切り替えはプログラムを下ろすときに行うためです。

```clojure
(println (in-ns 'demo)) ; nil
(clojure.core/println (clojure.core/str clojure.core/*ns*)) ; demo
```
