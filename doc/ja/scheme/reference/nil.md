# nil

`nil`

空リスト `'()` を保持する変数です。Common Lisp の `nil` と違って偽ではありません。Scheme で偽なのは `#f` だけなので、条件式の `nil` は真とみなされます。リテラルではなく通常の変数です。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
nil ; => ()
(eq? nil '()) ; => #t
(if nil 'yes 'no) ; => yes
```
