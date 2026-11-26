# exit

`(exit)` `(exit obj)`

プログラムを終了します。まず呼び出しを囲むすべての `dynamic-wind` の `after` が実行され、出力がフラッシュされてからプロセスが終了します。引数なしまたは `#t` は終了ステータス 0、`#f` は 1、整数はその下位 8 ビットになります（`(exit 258)` はステータス 2）。

```scheme
(display "working")
(newline)
(dynamic-wind
  (lambda () #f)
  (lambda () (exit 3))
  (lambda () (display "cleanup") (newline)))
(display "never printed")
```

```
working
cleanup
```
