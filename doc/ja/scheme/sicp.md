# SICP 互換

*[Structure and Interpretation of Computer Programs](https://sarabander.github.io/sicp/html/index.xhtml)*
(SICP、Harold Abelson と Gerald Jay Sussman による MIT の教科書) が、これらの名前と、
それが書かれた方言である MIT Scheme の出どころです。

**R7RS ではありません。** MIT Scheme の `true`、`false`、`nil`（リテラルではなく普通の変数）、
`user-initial-environment` と `system-global-environment`（どちらも唯一の大域環境を指す。
[eval](eval.md)を参照）、`filter`、`fold-left`、`1+` などのリストと数値のユーティリティ、
`runtime` と `random`、`parallel-execute` と `test-and-set!`、そして SICP のストリーム:
`cons-stream`（構文）とストリーム手続きです。それぞれのページは
[SICP 互換の名前](reference/library-sicp.md)にあり、R5RS の `scheme-report-environment`、
`exact->inexact`、`inexact->exact` は [(scheme r5rs)](reference/library-r5rs.md) にあります。ストリームは `'()` か、cdr が
プロミスであるペアなので、`the-empty-stream` は `'()`、`stream-null?` は `null?` です。
これらは `(import ...)` を
一切書かないプログラムでのみ見える -- 6 ライブラリと同じ扱いだが、どの import もこれらを
名指しできないため、明示的な import リストがあると届かない。
[`--scheme-standard r7rs`](standards.md) ではどこからも見えない。

```scheme
(display (list true false nil (cadddr '(1 2 3 4)))) (newline)
(display (filter odd? '(1 2 3 4 5))) (newline)
(display (fold-left cons '() '(1 2 3))) (newline)
```

```
(#t #f () 4)
(1 3 5)
(((() . 1) . 2) . 3)
```

`parallel-execute` はインタプリタと JVM では各サンクをそれぞれのスレッドで実行し、すべてが
終わってから戻ります。サンク内のエラーはそのときに通知されます。WebAssembly にはスレッドが
ないため、サンクは引数の順に一つずつ実行されます。これはスレッド実行でも起こりうる実行順序の
一つで、`test-and-set!` の上に作ったシリアライザが待たされることはありません。`test-and-set!`
はどのバックエンドでもアトミックです。

```scheme
(define cell (list false))
(display (list (test-and-set! cell) (test-and-set! cell))) (newline)
(define finished '())
(define (finish name) (lambda () (set! finished (cons name finished))))
(parallel-execute (finish 'only))
(display finished) (newline)
```

```
(#f #t)
(only)
```

プロミスは一度だけ評価され、その値を覚えています。ストリームをたどると各セルは一度だけ評価されます。

```scheme
(define (integers-from n) (cons-stream n (integers-from (+ n 1))))
(define (sieve s)
  (cons-stream (stream-car s)
               (sieve (stream-filter (lambda (x) (not (= 0 (remainder x (stream-car s)))))
                                     (stream-cdr s)))))
(display (stream-head (sieve (integers-from 2)) 10)) (newline)
(define p (delay (begin (display "once ") 42)))
(display (list (force p) (force p))) (newline)
```

```
(2 3 5 7 11 13 17 19 23 29)
once (42 42)
```
