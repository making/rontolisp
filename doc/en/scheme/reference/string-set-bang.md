# string-set!

`(string-set! string k char)`

Stores `char` at index `k` of `string`, returning the unspecified value. Every string is mutable, string literals included: R7RS makes modifying a literal an error, rontolisp does not refuse it.

```scheme
(let ((s (make-string 3 #\a))) (string-set! s 1 #\b) s) ; => "aba"
(define s (make-string 3 #\a))
(string-set! s 1 #\b)
s ; => "aba"
```
