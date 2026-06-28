# prog1

`(prog1 first body...)`

Evaluates `first` and all the body forms in order, then returns the value of `first`. It is useful for capturing a value before the side effects in the rest of the body run -- for example, reading an old value out of a place before updating it.

```lisp
(let ((x 10)) (prog1 x (setq x 20))) ; => 10
```
