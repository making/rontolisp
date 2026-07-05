# declare

`(declare declaration...)`

Declarations are parsed no-ops: the whole `declare` form evaluates to nil and its arguments are never evaluated or validated, so any standard declaration (`ignore`, `ignorable`, `type`, `optimize`, `inline`, `special`, ...) is accepted anywhere in a body. This exists so source code written for other Common Lisp implementations loads unchanged; no declaration has any effect.

```lisp
(let ((x 10))
  (declare (type integer x) (optimize (speed 3)))
  (* x 2)) ; => 20
```
