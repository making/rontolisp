# cond

`(cond (test expression...)... [(else expression...)])` `(cond (test => receiver)...)`

Evaluates the `test`s in order and, at the first true one, evaluates that clause's expressions and answers the last value; a clause with no expressions answers the test's value. In a `(test => receiver)` clause, `receiver` is called with the test's value. An `else` clause matches when nothing before it did. When no clause is taken the value is unspecified.

```scheme
(cond ((> 1 2) 'a) ((> 2 1) 'b) (else 'c)) ; => b
(cond ((assv 'b '((a 1) (b 2))) => cadr) (else #f)) ; => 2
(cond ((+ 1 1))) ; => 2
```
