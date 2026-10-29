# resolve

`(resolve sym)`

シンボルが現在の名前空間で指す var、クラス名が表すクラス、または `nil` を返します。
quote したシンボルはプログラムの lower 中に解決され、`#'name` が lower する var に
なります。プログラムの var、このフロントエンドが実装する `clojure.core` の var、
core のマクロ、低レベル化として実装するライブラリの var（`clojure.string/upper-case`）です。それ以外の名前は `nil` で、このフロントエンドにない core の var も
含みます。そのため、動作中の `clojure.core` によってコードを選ぶライブラリ
（`resolve` を使う `compile-if`）は、ここで lower できるコードを選びます。計算で得た
シンボルはマクロ本体の中と、インタプリタでは呼び出しの実行時に `*ns*` の名前空間で解決
されます。コンパイル済みプログラムでは実行時に `UnsupportedOperationException` を
投げます。`(resolve env sym)` は現在の名前空間での [ns-resolve](ns-resolve.md) で、環境（マクロの
`&env`）が持つシンボルには nil を返します。関数値としても使えます。

```clojure
(defn greet [] "hi")
(println (resolve 'greet) (resolve 'inc) (resolve 'nowhere))
```

```
#'user/greet #'clojure.core/inc nil
```
