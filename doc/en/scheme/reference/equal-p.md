# equal?

`(equal? obj1 obj2)`

Compares structure: pairs and vectors element by element, strings by their characters, everything else as `eqv?` does. `(equal? 2 2.0)` is therefore `#f`. Records made by `define-record-type` are compared by identity, not field by field.

```scheme
(equal? '(1 (2 #(3))) '(1 (2 #(3)))) ; => #t
(equal? "abc" "abc") ; => #t
(equal? 2 2.0) ; => #f
```
