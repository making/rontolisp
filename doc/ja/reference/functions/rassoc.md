# rassoc

`(rassoc value alist &key test key)`

連想リストを検索し、cdr が `value` に一致する最初のペアを返します。一致するものがなければ `nil` を返します。car で検索する `assoc` の対になる関数です。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと、別の比較関数を使えます。省略可能な `:key` キーワードに渡したセレクタ関数は、比較の前に各ペアの cdr へ適用されます。返されるペアは連想リストと構造を共有します。

```lisp
(rassoc 2 '((a . 1) (b . 2))) ; => (b . 2)
```

```lisp
(rassoc "x" '((a . "w") (b . "x")) :test #'equal) ; => (b . "x")
```

```lisp
(rassoc 2 '((a . 1) (b . 3)) :key (lambda (v) (- v 1))) ; => (b . 3)
```
