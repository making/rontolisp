# delay

`(delay body...)`

本体の遅延値です。本体は最初の [force](force.md) か [deref](deref.md)（`@d`）で一度だけ
実行され、以降はすべて同じ値を返します。本体が例外を投げた場合、以降の force は毎回同じ例外を
投げ直し、遅延値は実現済みになります（オラクルと同じ）。本体は遅延値自身のロックの下で実行される
ため、Ring ハンドラの2つのリクエストスレッドが同じ遅延値を force しても本体は一度だけ実行されます
（インタプリタと JVM。wasm モジュールはシングルスレッドです）。
[realized?](realized-p.md) は本体が実行済みかを返し、[delay?](delay-p.md) は `true` を返します。
`clojure.lang.Delay`・`IDeref`・`IPending` の `instance?` は真、`=` は同一性、`class` は
`:clojure.lang.Delay` を返します。タイムアウト付きの `deref` は、オラクルの future へのキャストと
同じく `ClassCastException` を投げます。遅延値はオラクルの
`#object[clojure.lang.Delay {:status :pending, :val nil}]` として印字され（force 後は `:ready` と
その値、例外の後は `:failed` とその例外）、`str` は `clojure.lang.Delay` です。どちらも同一性ハッシュは
含みません。`delay` はマクロなので値を持ちません。

```clojure
(def config (delay (println "loading") {:port 8080}))
(println (realized? config)) ; false
(println (:port @config) (:port (force config))) ; loading, then 8080 8080
(println (realized? config)) ; true
```
