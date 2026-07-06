# define-setf-expander

`(define-setf-expander name lambda-list body...)`

パース済み no-op として受理され `nil` を返します。5 値の setf 展開プロトコル (`get-setf-expansion` / `&environment`) は実装されていないため、この方法で定義された場所は `setf` の対象として使用できません。

```lisp
(define-setf-expander my-place (x) x) ; => nil
```
