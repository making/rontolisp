# refer

`(refer ns-sym & filters)`

名前空間 `ns-sym` を読んで今の名前空間へ refer する。そのままでは公開 var 全部。`:only`、`:exclude`、`:rename` で絞る。`nil` を返す。oracle と同じ。求まる形でないものはインタプリタでは呼び出しの実行時に答え（インタプリタはプログラムの lower を実行中も保持する）、コンパイル済みプログラムでは実行時に拒否される。値としては何引数でもよい。

```clojure
(refer 'clojure.string :only '[upper-case])
(println (upper-case "ab")) ; AB
```
