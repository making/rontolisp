# instance?

`(instance? Class x)`

`x` がそのクラスのとき `true` です。中心的なクラスのみ対応します（`String`、
`CharSequence`、`Character`、`Boolean`、`Number`、`Long`、`Double`、`Object`、
`clojure.lang.Keyword`、`clojure.lang.Symbol`。あるものは `java.lang.` 綴りも可）。
他のクラスは誤答の代わりに名前付きで拒否されます（wasm バックエンドにホストの幅は
ないため）。

```clojure
(println (instance? String "a") (instance? String 1)) ; true false
```
