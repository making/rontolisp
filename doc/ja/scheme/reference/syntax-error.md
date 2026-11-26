# syntax-error

`(syntax-error message argument...)`

文字列 `message` に、書かれたままの `argument` を続けたものを、ファイルを読む時点のエラーとして報告します。テンプレートで使い、ほかの規則が受け付けない利用について何が誤りかを示すためのものです。

```scheme
(define-syntax one-arg
  (syntax-rules ()
    ((_ a) a)
    ((_ . rest) (syntax-error "one-arg takes one argument" rest))))
(one-arg 5) ; => 5
```
