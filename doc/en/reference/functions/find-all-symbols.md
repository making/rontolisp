# find-all-symbols

`(find-all-symbols symbol &optional package)`

Every distinct symbol whose name is `string=` to `symbol`'s, accessible in
`package` -- or, by default, in any registered package. Each answer is spelled
the way code spells it (a `cl` member reads bare), so `member` tests against the
result work. Keywords are never listed: without an intern table there is no
keyword universe to walk.

A symbol the program only reads counts, on every backend. A compiled program has
read all of itself before it runs, so it finds a name its source reads later too;
the interpreter finds what it has read so far.

```lisp
(find-all-symbols 'car) ; => (CAR)
(find-all-symbols 'car :cl-user) ; => (CAR)
```
