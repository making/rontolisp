# dolist

`(dolist (var list [result]) body...)`

Evaluates `list` once and runs the body repeatedly with `var` bound to each successive element. After the list is exhausted, `var` is bound to nil and the optional `result` form is evaluated and returned (nil if omitted). The body is wrapped in the internal block boundary, so `return` inside it exits the loop early.

```lisp
(dolist (x '(a b c)) (format t "~a~%" x))
```

```
a
b
c
```
