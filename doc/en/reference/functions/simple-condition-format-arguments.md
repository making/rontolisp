# simple-condition-format-arguments

`(simple-condition-format-arguments condition)`

The `:format-arguments` slot of a condition instance (nil when the condition has no such slot).

```lisp
(simple-condition-format-arguments
 (make-condition 'simple-error :format-control "boom ~a" :format-arguments '(1))) ; => (1)
```
