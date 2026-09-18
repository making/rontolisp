# append!

`(append! list ...)`

Appends the lists destructively: the last pair of each non-empty list is changed to point at the next list, and the result shares structure with its arguments. Use it only on lists you built yourself, never on a quoted literal. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(append! (list 1 2) (list 3 4)) ; => (1 2 3 4)
(define a (list 1 2))
(append! a (list 3))
a ; => (1 2 3)
```
