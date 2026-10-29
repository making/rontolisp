# ns-aliases

`(ns-aliases ns)`

名前空間 `ns`（シンボル、文字列、`*ns*`）の別名。各別名とその名前空間オブジェクトのマップ。oracle と同じ。下ろしながら分かるものだけが載り、求まる形でないものはインタプリタでは呼び出しの実行時に答え（インタプリタはプログラムの lower を実行中も保持する）、コンパイル済みプログラムでは実行時に拒否される。値としては 1 引数を取る。

```clojure
(alias 'doc-eg-s 'clojure.string)
(println (ns-name (get (ns-aliases 'user) 'doc-eg-s))) ; clojure.string
```
