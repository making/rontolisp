# syntax-error

`(syntax-error message argument...)`

Reports `message`, a string, followed by the `argument`s as written, as an error when the file is read. Meant for a template, so that a use no other rule accepts names what is wrong with it.

```scheme
(define-syntax one-arg
  (syntax-rules ()
    ((_ a) a)
    ((_ . rest) (syntax-error "one-arg takes one argument" rest))))
(one-arg 5) ; => 5
```
