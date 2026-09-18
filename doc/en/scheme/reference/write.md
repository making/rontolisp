# write

`(write obj)`

Writes `obj` to the current output port in its external representation: strings in quotes with escapes, characters in `#\` notation. A circular list or vector is written with datum labels; structure shared without a cycle is written out each time. `'x` is written as `(quote x)` and the unspecified value as `#!unspecific`. There is no port argument.

```scheme
(write '(1 "two" #\3))
(newline)
(define c (list 'a 'b))
(set-cdr! (cdr c) c)
(write c)
(newline)
```

```
(1 "two" #\3)
#0=(a b . #0#)
```
