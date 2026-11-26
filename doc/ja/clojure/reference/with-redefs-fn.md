# with-redefs-fn

`(with-redefs-fn binding-map func)`

マップの各 var を値に結び直して `func` を呼ぶ。終われば戻す。`bindRoot` の検査と通知つき。oracle と同じ。根の setter を持つ var は値セルに書くため直接の呼び出しにも見える。持たない var は getter を差し替えるため、var 越しの読みにだけ見える（oracle の静的リンクと同じ）。値としては 2 引数を取る。

```clojure
(def doc-eg-r 1)
(println (with-redefs-fn {(var doc-eg-r) 99} (fn [] doc-eg-r))) ; 99
```
