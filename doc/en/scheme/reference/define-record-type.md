# define-record-type

`(define-record-type name (constructor field...) predicate (field accessor [modifier])...)`

Defines a record type: `constructor` makes a record from the listed fields, `predicate` tests for one, and each `accessor` (and optional `modifier`) reads (and writes) a field. Allowed at the top level and in a body. A record writes in Common Lisp's `#S(...)` syntax, with each field named after its accessor, and `equal?` compares records by identity.

```scheme
(define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))
(define p (make-point 3 4))
(set-point-x! p 10)
(write (list (point-x p) (point-y p) (point? p) (point? 5)))
(newline)
(write p)
(newline)
```

```
(10 4 #t #f)
#S(point :point-x 10 :point-y 4)
```

In a body its names are local to the body, and a record prints its type qualified by the top-level definition it stands in, each field named after the field:

```scheme
(define (tagged v)
  (define-record-type tag (make-tag v) tag? (v tag-value))
  (make-tag v))
(write (tagged 1))
(newline)
```

```
#S(s%%[tagged tag] :v 1)
```
