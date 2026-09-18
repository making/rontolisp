# stream-pair?

`(stream-pair? obj)`

`obj` が空でないストリーム、つまり cdr がプロミスであるペアなら `#t`、そうでなければ `#f` を返します。通常のリストはストリームのペアではありません。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-pair? (stream 1)) ; => #t
(stream-pair? '(1 2)) ; => #f
(stream-pair? '()) ; => #f
```
