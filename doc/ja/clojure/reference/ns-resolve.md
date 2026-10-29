# ns-resolve

`(ns-resolve ns sym)` / `(ns-resolve ns env sym)`

`clojure.core/ns-resolve`: その名前空間でのシンボルの [resolve](resolve.md) の答え、つまり
var、クラス名がロードするクラス、または nil です。クォートしたシンボルは、名前空間がクォートした
名前、`(find-ns 'n)`、`(the-ns 'n)`、`*ns*`（呼び出しが lower される名前空間）のとき、プログラムの
lower 中に解決します。存在しない名前空間は oracle と同じく失敗します。`(find-ns 'n)` の nil は
`NullPointerException`、名前は `No namespace: n found` です。シンボルを持つ環境（その名前の
ローカルを持つマクロの `&env`）には nil を返します。計算した名前空間やシンボルを解決するのは
マクロ本体の中と、インタプリタでの呼び出しの実行時です。コンパイル済みプログラムでは実行時に
計算したシンボルの `resolve` と同じく拒否します（nil の名前空間は先に `NullPointerException` です）。値としては2引数または3引数の関数です。

```clojure
(defn helper [] 1)
(println (ns-resolve 'user 'helper) (ns-resolve 'clojure.core 'inc) (ns-resolve 'user 'nowhere)) ; #'user/helper #'clojure.core/inc nil
(println (try (ns-resolve (find-ns 'absent.lib) 'x) (catch Exception e :no-such-namespace))) ; :no-such-namespace
```
