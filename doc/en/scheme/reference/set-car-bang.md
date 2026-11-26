# set-car!

`(set-car! pair obj)`

Stores `obj` in the car of `pair`. Called for its effect: the REPL shows nothing, and its value written out is `#!unspecific`.

```scheme
(let ((p (list 1 2))) (set-car! p 9) p) ; => (9 2)
(define p (list 1 2))
(set-car! p 9)
p ; => (9 2)
```
