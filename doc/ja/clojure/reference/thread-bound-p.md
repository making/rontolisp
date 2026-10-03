# thread-bound?

`(thread-bound? & vars)`

`clojure.core/thread-bound?`: 与えたすべての var について `binding` が有効な間だけ `true` を返します。ルート値、`^:dynamic` でない var、どの `binding` も再束縛していない `^:dynamic` var は `false` です。var でない値はオラクルと同様にシグナルを上げます（最初の未束縛の var で `false` を返すため、そこまで評価が進んだ場合のみ）。var を与えなければ `true` です。値としては任意個の var をとる関数です。`#'*out*`、`#'*in*`、`#'*agent*` はルートでは `false`（オラクルの `clojure.main` はどれも束縛しません）で、その特殊変数の `binding` の中、`with-out-str` の中（`*out*`）、エージェントのアクションの中（`*agent*`）で `true` です。それ以外の `clojure.core` の var は `false` です。

```clojure
(def ^:dynamic *tb* 1)
(println (thread-bound? #'*tb*)
         (binding [*tb* 2] (thread-bound? #'*tb*)))  ; false true
(println (thread-bound? #'*out*)
         (with-out-str (print (thread-bound? #'*out*))))  ; false true
```
