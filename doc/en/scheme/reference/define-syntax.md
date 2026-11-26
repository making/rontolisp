# define-syntax

`(define-syntax keyword (syntax-rules ...))`

Binds `keyword` to a macro: a use `(keyword ...)` is replaced by the expansion its transformer answers before the program runs. Allowed at the top level and at the head of a body; a macro may expand into definitions. The macro is visible from its definition to the end of the file (or the body); a `(load ...)`ed file neither sees nor exports it. Only `syntax-rules` transformers are accepted. Macros are hygienic: a variable the template binds captures no name the use wrote, and a name the template uses freely means what it meant where the macro was defined.

```scheme
(define-syntax swap!
  (syntax-rules ()
    ((_ a b) (let ((tmp a)) (set! a b) (set! b tmp)))))
(define x 1)
(define tmp 2)
(swap! x tmp)
(list x tmp) ; => (2 1)
(let () (define-syntax two (syntax-rules () ((_) 2))) (* (two) 3)) ; => 6
```
