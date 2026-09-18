# test-and-set!

`(test-and-set! cell)`

ペア `cell` の car を不可分に検査して設定します。`#f` なら `#t` にして `#f` を返し、そうでなければ変更せずに `#t` を返します。検査と設定の間に他のスレッドが割り込むことはどのバックエンドでもありません。SICP がミューテックスを組み立てる基本操作です。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(test-and-set! (list #f)) ; => #f
(define cell (list #f))
(test-and-set! cell) ; => #f
(test-and-set! cell) ; => #t
cell ; => (#t)
```
