# instance?

`(instance? Class x)`

`x` がそのクラスのとき `true` です。中心的なクラスのみ対応します（`String`、
`CharSequence`、`Character`、`Boolean`、`Number`、`Long`、`Double`、`Object`、
`clojure.lang.Keyword`、`clojure.lang.Symbol`。あるものは `java.lang.` 綴りも可）。
他のクラスは誤答の代わりに名前付きで拒否されます（wasm バックエンドにホストの幅は
ないため）。既知の record/deftype 名はディスパッチタグの検査になります。
throwable クラス（`Exception`、`IllegalArgumentException`、`clojure.lang.ExceptionInfo`、
ドット付きや import した名前）は、例外と実行時エラーをそのクラス（`class` が返すクラス）か
そのサブクラスであるかで検査し、インタプリタと JVM のホストの `Throwable` はホストのクラスで
検査します。

```clojure
(println (instance? String "a") (instance? String 1)) ; true false
(println (instance? RuntimeException (IllegalArgumentException. "x")) (instance? RuntimeException (Exception. "x"))) ; true false
```
