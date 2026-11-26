# memfn

`(memfn name arg...)`

インスタンス呼び出しの上のラムダです。`(memfn m a...)` は対象と名付けられた引数を取り、`(m target a...)` をそれに対して呼ぶため、メソッド名だけでは渡れない場所へ `map`/`apply` が渡れます。文字列の対象は、任意のインスタンス呼び出しと同じく、対応する core 操作を取ります。インタープリターと JVM で動きます。対応する文字列操作は 4 つのバックエンドすべてで動きます。

```clojure
(println ((memfn toUpperCase) "hi")) ; HI
(println ((memfn substring s e) "hello" 1 2)) ; e
(println (map (memfn length) ["a" "bb"])) ; (1 2)
```
