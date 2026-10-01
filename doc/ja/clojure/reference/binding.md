# binding

`(binding [var init ...] body...)`

本体の周りで各 var を動的エクステントで再束縛します。本体から呼ばれるコードにも束縛が見え、終わればルートに戻ります。束縛できるのは `^:dynamic` な var（と、もとから special な `*out*`/`*in*`）だけで、それ以外はオラクルの非 dynamic エラー同様に拒否されます。初期化は `let` 同様に逐次です。

```clojure
(def ^:dynamic *loud* false)
(defn status [] (if *loud* :loud :quiet))
(println (status)) ; :quiet
(println (binding [*loud* true] (status))) ; :loud
(println (status)) ; :quiet
```

`*in*` は `*standard-input*` です（`nil` になることはありません）。再束縛すれば `(.readLine *in*)` のような読み手に供給できます。ストリーム
上では `read-line` 越しに読み、末尾越しは `nil` を答えます。

```clojure
(println (nil? *in*)) ; false
(println (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "hi"))] (.readLine *in*))) ; hello
```
