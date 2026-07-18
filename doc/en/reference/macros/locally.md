# locally

`(locally declaration... form...)`

Evaluates the body as a `progn`. Declarations are parsed no-ops everywhere in rontolisp ([`declare`](declare.md)/[`the`](the.md)), so `locally` simply drops its leading `declare` forms and evaluates the rest — code that uses `locally` to scope real Common Lisp declarations runs unchanged.

```lisp
(locally
  (declare (optimize (speed 3)))
  (+ 40 2)) ; => 42
```
