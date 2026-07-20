# declaim

`(declaim declaration...)`

File-level declarations, parsed as a no-op like `declare`: the form evaluates to nil and the declarations (`optimize`, `inline`, `type`, `special`, ...) are neither evaluated nor validated. It exists so library sources using `declaim` load unchanged.

```lisp
(declaim (optimize (speed 3) (safety 1))) ; => NIL
```
