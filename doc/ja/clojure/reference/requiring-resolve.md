# requiring-resolve

`(requiring-resolve sym)`

修飾シンボル `sym` の名前空間を読んでその var を返す。何も指さないものは `nil`。oracle と同じ。クォートしたシンボルはプログラムを下ろしながら解ける。求まる形でないものはインタプリタでは呼び出しの実行時に答え（インタプリタはプログラムの lower を実行中も保持する）、コンパイル済みプログラムでは実行時に拒否される。値としては 1 引数を取る。

```clojure
(println (requiring-resolve 'clojure.string/join)) ; #'clojure.string/join
```
