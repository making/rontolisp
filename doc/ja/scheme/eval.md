# eval

`(eval datum env)` はデータを実行時に評価します。どのバックエンドでも動きます。環境指定子は
どれも唯一の大域環境です: `(interaction-environment)`、`(scheme-report-environment 5)`、
`(environment '(scheme base) ...)` -- その import 集合は[ライブラリ](libraries.md)に照らして検査されます --
および MIT Scheme の `user-initial-environment` と `system-global-environment` はすべて
これを指し、引数は省略できます（[`--scheme-standard r7rs`](standards.md) では省略不可）。大域環境が持つのは、プログラムの変数（`eval` 自身が
定義したものを含む）、プログラムの手続き、組み込み手続きで、この順に探されます。`eval` の中の `define` はプログラムの
大域変数になり、後の `eval` からもプログラム自身からも（`eval` 経由で）見えます。
プログラムの変数への `set!` はそれに代入します。プログラムは `eval` が書いた値を読みます。

```scheme
(define (execute exp) (apply (eval (car exp) user-initial-environment) (cdr exp)))
(display (execute '(> 5 3))) (newline)
(eval '(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (interaction-environment))
(display (list (eval '(fact 10) (interaction-environment))
               (eval '(let loop ((i 0)) (if (= i 100000) i (loop (+ i 1))))
                     (interaction-environment))))
(newline)
```

```
#t
(3628800 100000)
```

`eval` の中では、名前付き `let`、`do`、自分自身を呼ぶ手続きは一定のスタックで動きます。
それ以外の呼び出しはスタックを消費します。`define-record-type`、`define-values`、
`let-values`、`import`、およびリーダーが拒否する構文は `eval` の中でも名前を挙げて拒否されます。
コンパイルされたプログラムの `eval` が組み込み手続きを解決できるのは、プログラムがその名前を
どこかに綴っている場合 -- シンボルとして（クォートされたデータを含む）、または文字列の中に --
だけです。インタプリタはすべてを解決します。
