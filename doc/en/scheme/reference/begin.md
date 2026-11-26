# begin

`(begin expression...)`

Evaluates the expressions left to right and answers the value of the last one; `(begin)` answers the unspecified value. At the top level of a file or the REPL, `begin` splices its forms into the top level, so it may contain definitions, and the REPL echoes each of its forms separately.

```scheme
(let ((x 1)) (begin (set! x (* x 10)) (+ x 1))) ; => 11
(list (begin)) ; => (#!unspecific)
```
