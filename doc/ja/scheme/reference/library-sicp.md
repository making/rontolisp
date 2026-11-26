# SICP 互換の名前

*[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/ MIT Scheme の名前です。どの R7RS ライブラリもエクスポートしないため `import` では届かず、`import` のないファイルと REPL からだけ見え、[`--scheme-standard r7rs`](../standards.md) ではどこからも見えません。[SICP 互換](../sicp.md)を参照してください。

## 手続き

| 名前 | 例 | 結果 |
|---|---|---|
| `filter` | `(filter odd? '(1 2 3 4 5))` | `(1 3 5)` |
| `reduce` | `(reduce + 0 '(1 2 3 4))` | `10` |
| `fold-left` | `(fold-left cons '() '(1 2 3))` | `(((() . 1) . 2) . 3)` |
| `fold-right` | `(fold-right cons '() '(1 2 3))` | `(1 2 3)` |
| `delete` | `(delete 3 '(1 3 2 3))` | `(1 2)` |
| `last-pair` | `(last-pair '(1 2 3))` | `(3)` |
| `append!` | `(append! (list 1 2) (list 3 4))` | `(1 2 3 4)` |
| `list-index` | `(list-index even? '(1 3 4 5))` | `2` |
| `1+` | `(1+ 5)` | `6` |
| `-1+` | `(-1+ 5)` | `4` |
| `random` | `(random 1)` | `0` |
| `runtime` | `(real? (runtime))` | `#t` |

## 並行処理

| 名前 | 例 | 結果 |
|---|---|---|
| `parallel-execute` | `(parallel-execute (add! 1) (add! 10) (add! 100))` | 3 つのサンクを並行に実行し、その後 `total` は `111` |
| `test-and-set!` | `(test-and-set! (list #f))` | `#f` |

## ストリーム

| 名前 | 例 | 結果 |
|---|---|---|
| `cons-stream` | `(stream-car (cons-stream 1 (/ 1 0)))` | `1` |
| `stream-car` | `(stream-car (stream 1 2 3))` | `1` |
| `stream-cdr` | `(stream-car (stream-cdr (stream 1 2 3)))` | `2` |
| `stream-first` | `(stream-first (stream 7 8 9))` | `7` |
| `stream-rest` | `(stream-head (stream-rest (stream 7 8 9)) 2)` | `(8 9)` |
| `stream-pair?` | `(stream-pair? (stream 1))` | `#t` |
| `stream-null?` | `(stream-null? the-empty-stream)` | `#t` |
| `empty-stream?` | `(empty-stream? (stream))` | `#t` |
| `stream` | `(stream->list (stream 1 2 3))` | `(1 2 3)` |
| `list->stream` | `(stream->list (list->stream '(a b c)))` | `(a b c)` |
| `stream->list` | `(stream->list (stream 1 2 3))` | `(1 2 3)` |
| `stream-head` | `(stream-head (stream 1 2 3) 2)` | `(1 2)` |
| `stream-tail` | `(stream->list (stream-tail (stream 1 2 3) 1))` | `(2 3)` |
| `stream-ref` | `(stream-ref (stream 'a 'b 'c) 1)` | `b` |
| `stream-map` | `(stream->list (stream-map (lambda (x) (* x x)) (stream 1 2 3)))` | `(1 4 9)` |
| `stream-for-each` | `(stream-for-each (lambda (x) (display x) (newline)) (stream 1 2 3))` | `1`、`2`、`3` を 1 行ずつ出力 |
| `stream-filter` | `(stream->list (stream-filter odd? (stream 1 2 3 4 5)))` | `(1 3 5)` |
| `stream-append` | `(stream->list (stream-append (stream 1 2) (stream 3) (stream 4 5)))` | `(1 2 3 4 5)` |
| `the-empty-stream` | `the-empty-stream` | `()` |

## 定数

| 名前 | 例 | 結果 |
|---|---|---|
| `true` | `true` | `#t` |
| `false` | `false` | `#f` |
| `nil` | `nil` | `()` |
| `user-initial-environment` | `(eval '(+ 1 2) user-initial-environment)` | `3` |
| `system-global-environment` | `(eval '(* 2 3) system-global-environment)` | `6` |
