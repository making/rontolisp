# alias

`(alias alias-sym ns-sym)`

今の名前空間に別名 `alias-sym` を名前空間 `ns-sym` へ結ぶ。以後の形の `alias/var` も `::alias/kw` も解ける。`nil` を返す。oracle と同じ。求まる形でない名前は、インタプリタでは呼び出しの実行時に結ばれ（インタプリタはプログラムの lower を実行中も保持する）、コンパイル済みプログラムでは実行時に拒否される。値としては 2 引数を取る。

```clojure
(alias 'doc-eg-s2 'clojure.string)
(println (doc-eg-s2/join "-" ["a"])) ; a-b
```
