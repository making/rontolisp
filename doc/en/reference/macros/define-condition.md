# define-condition

`(define-condition name (parent...) (slot...) option...)`

Accepted as a parsed no-op returning `nil`: there is no condition system, so the condition type is not registered anywhere. Pairs with the lite `make-condition` so the common `(error (make-condition 'type :format-control "..."))` idiom still signals with the intended message.

```lisp
(define-condition my-parse-error (error) ()) ; => nil
```
