# eval

`(eval form)`

データとして渡したフォームを評価します。マクロ本体、またはそこから呼ぶ関数の中で、
フォームは展開位置の名前空間で、呼び出し位置のローカルを見ずに lower され、呼び出し
位置より上の定義の上で評価されます。プログラムの実行中は、プログラムの lower を保持する
インタプリタが、フォームを `*ns*` の名前空間で lower して評価します。それが定義したものは
以後の `eval`、`resolve`、REPL の入力から指せ、リテラルで綴れない値（関数、atom）はその値
自身としてフォームに入り、lower できないフォームは lower の文言の
`clojure.lang.Compiler$CompilerException` を投げます。コンパイル済みプログラムは lower の
仕組みを持たないため、そこでは実行時の `eval` は `UnsupportedOperationException` を投げます。
関数値としても使えます。

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
