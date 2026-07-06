# define-setf-expander

`(define-setf-expander name lambda-list body...)`

Accepted as a parsed no-op returning `nil`: the full five-value setf-expansion protocol (`get-setf-expansion` / `&environment`) is not implemented, so a place defined this way cannot be used as a `setf` target.

```lisp
(define-setf-expander my-place (x) x) ; => nil
```
