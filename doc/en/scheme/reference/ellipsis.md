# ...

`pattern ...` `template ...`

Auxiliary syntax of `syntax-rules`: in a pattern it matches the subpattern before it zero or more times, in a template it repeats the subtemplate before it once per match. It means nothing on its own.

```scheme
(let-syntax ((my-list (syntax-rules () ((_ e ...) (list e ...))))) (my-list 1 2 3)) ; => (1 2 3)
(define-syntax my-list-of-lists
  (syntax-rules ()
    ((_ (a ...) ...) '((a ... end) ...))))
(my-list-of-lists (1 2) (3)) ; => ((1 2 end) (3 end))
```
