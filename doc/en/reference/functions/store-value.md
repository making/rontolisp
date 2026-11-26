# store-value

`(store-value value [condition])`

Invokes the innermost active `store-value` restart with `value`, and returns `nil` when none is active — the sibling of [`use-value`](use-value.md) (CL pairs the two: `use-value` supplies a one-off replacement, `store-value` asks the signaler to also store it).

```lisp
(handler-bind ((error (lambda (c) (store-value 7))))
  (restart-case (error "no value")
    (store-value (v) (list :stored v)))) ; => (:STORED 7)
```
