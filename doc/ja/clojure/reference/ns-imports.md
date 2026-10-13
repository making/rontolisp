# ns-imports

`(ns-imports ns)`

名前空間 `ns`（シンボル、文字列、`*ns*`）の import したクラス。既定の `java.lang` に明示の import を重ねる。各単純名とそのクラスのシンボルのマップ。oracle のマップからクラスをシンボルにしたもの（クラスはホストバックエンドにしかない）。下ろしながら分かるものだけが載り、求まる形でないものは実行時に拒否される。値としては 1 引数を取る。

```clojure
(println (get (ns-imports 'user) 'String)) ; java.lang.String
```
