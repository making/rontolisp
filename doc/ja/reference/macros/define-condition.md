# define-condition

`(define-condition name (parent...) (slot...) option...)`

パース済み no-op として受理され `nil` を返します。コンディションシステムは存在しないため、コンディション型はどこにも登録されません。簡易版 `make-condition` と対になっており、よくある `(error (make-condition 'type :format-control "..."))` イディオムは意図したメッセージでシグナルされます。

```lisp
(define-condition my-parse-error (error) ()) ; => nil
```
