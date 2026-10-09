# resolve

`(resolve sym)`

シンボルが現在の名前空間で指す var、クラス名が表すクラス、または `nil` を返します。
quote したシンボルはプログラムの lower 中に解決され、`#'name` が lower する var に
なります。プログラムの var、このフロントエンドが実装する `clojure.core` の var、
core のマクロです。それ以外の名前は `nil` で、このフロントエンドにない core の var も
含みます。そのため、動作中の `clojure.core` によってコードを選ぶライブラリ
（`resolve` を使う `compile-if`）は、ここで lower できるコードを選びます。計算で得た
シンボルはマクロ本体の中でだけ解決でき、実行時は `UnsupportedOperationException` を
投げます。`(resolve env sym)` は拒否します。関数値としても使えます。

```clojure
(defn greet [] "hi")
(println (resolve 'greet) (resolve 'inc) (resolve 'nowhere))
```

```
#'user/greet #'clojure.core/inc nil
```
