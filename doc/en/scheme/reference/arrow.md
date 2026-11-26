# =>

`(cond (test => receiver)...)` `(case key ((datum...) => receiver)...)`

Auxiliary syntax in a `cond` or `case` clause: instead of evaluating expressions, the clause calls `receiver`, a one-argument procedure, with the test's value (`cond`) or the key (`case`), and answers its result. It has no meaning on its own.

```scheme
(cond ((assv 'b '((a 1) (b 2))) => cadr) (else #f)) ; => 2
(case 2 ((1 2) => (lambda (v) (* v 10))) (else 'no)) ; => 20
```
