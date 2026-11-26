# case

`(case key ((datum...) expression...)... [(else expression...)])`

Evaluates `key` and takes the first clause one of whose datums is `eqv?` to it, answering the value of its last expression. An `else` clause matches anything. A clause may be `((datum...) => receiver)` or `(else => receiver)`, which calls `receiver` with the key. When no clause is taken the value is unspecified. A string datum matches only that same string object, so a computed key never matches it (see `eqv?`).

```scheme
(case (* 2 3) ((2 3 5 7) 'prime) ((1 4 6 8 9) 'composite) (else 'other)) ; => composite
(case #\a ((#\a #\e) 'vowel) (else 'consonant)) ; => vowel
(case 'x ((a) 1) (else => (lambda (v) (list v 'unknown)))) ; => (x unknown)
(case (string #\a) (("a") 'string) (else 'no)) ; => no
```
