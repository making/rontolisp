# class

`(class x)`

値の種類をキーワードで返します。`:map`・`:vector`・`:set`・`:list`・`:string`・
`:number`・`:keyword`・`:symbol`・`:char`・`:boolean`・`:nil`・`:function`・`:atom` です。
オラクルはホストクラスを返しますが、wasm バックエンドにはないため、全バックエンド共通で
種類名のキーワードを返します。値としては1引数ラムダです。

```clojure
(println (class "a") (class 1)) ; :string :number
```
