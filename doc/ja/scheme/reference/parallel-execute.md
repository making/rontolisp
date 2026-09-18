# parallel-execute

`(parallel-execute thunk ...)`

各サンク（引数なしの手続き）を並行に実行し、すべてが終わってから戻ります。サンク内のエラーはその時点で通知されます。インタプリタと JVM では各サンクが独自のスレッドで動きます。WebAssembly にはスレッドがないため、サンクは引数の順に 1 つずつ実行されます。これはスレッド実行でも起こりうるインターリーブの 1 つです。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(define total 0)
(define cell (list false))
(define (add! n)
  (lambda ()
    (let wait () (if (test-and-set! cell) (wait)))
    (set! total (+ total n))
    (set-car! cell false)))
(parallel-execute (add! 1) (add! 10) (add! 100))
(display total)
(newline)
```

```
111
```
