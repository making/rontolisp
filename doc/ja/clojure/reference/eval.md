# eval

`(eval form)`

データとして渡したフォームを評価します。マクロ本体、またはそこから呼ぶ関数の中で、
フォームは展開位置の名前空間で、呼び出し位置のローカルを見ずに lower され、呼び出し
位置より上の定義の上で評価されます。プログラムの実行中は、プログラムの lower を保持する
インタプリタが、フォームを `*ns*` の名前空間で lower して評価します。それが定義したものは
以後の `eval`、`resolve`、REPL の入力から指せ、リテラルで綴れない値（関数、atom）はその値
自身としてフォームに入り、lower できないフォームは lower の文言の
`clojure.lang.Compiler$CompilerException` を投げます。JVM のクラスも同じです。クラスは
プログラムとコンパイラを持ち、実行時の最初の `eval` でプログラムを lower し直し、各フォームを
自身にリンクしたクラスへコンパイルします。そのため `.class` 出力はコンパイラを隣に、`.jar` は
内部に持ちます。コンパイラを持つのは、実行中に `eval` を呼びうるプログラムだけです。マクロ本体の
`eval`、またはマクロ本体からだけ呼ぶ関数の `eval` はプログラムの lower 中に動くため、それだけでは
クラスはコンパイラを持ちません。プログラムが実行中にマクロを展開するか、そのマクロの var を取る
場合は、そのマクロ本体が実行中にも動きうるため、クラスはコンパイラを持ちます。wasm モジュールは
lower の仕組みを持たないため、そこでは実行時の `eval` は
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
