# eval

`(eval form)`

データとして渡したフォームを評価します。動くのはプログラムの lower 中だけです。
マクロ本体、またはそこから呼ぶ関数の中で、フォームは展開位置の名前空間で、呼び出し
位置のローカルを見ずに lower され、呼び出し位置より上の定義の上で評価されます。
コンパイル済みプログラムは lower の仕組みを持たないため、実行時の `eval` は
`UnsupportedOperationException` を投げます。関数値としても使えます。

```clojure
(defmacro compile-if [test then else] (if (eval test) then else))
(def limit 3)
(println (compile-if (> limit 2) :big :small))
(println (compile-if (resolve 'clojure.core/inc) :has :lacks))
```

```
:big
:has
```
