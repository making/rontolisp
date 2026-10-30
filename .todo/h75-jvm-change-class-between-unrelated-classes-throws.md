# h75. JVM: a literal `change-class` between two unrelated classes throws ArrayIndexOutOfBoundsException

Difficulty: Medium

Found 2026-10-31 while working h70. On the JVM backend, a literal
`(change-class p 'other ...)` where `other` shares no superclass with `p`'s class
throws `java.lang.ArrayIndexOutOfBoundsException`; the interpreter changes the class
(CLHS 7.2: slots of the same name keep their values, the rest are initialized).

```lisp
(defclass a () ((x :initarg :x :accessor x)))
(defclass b () ((x :accessor x) (y :initform 2 :accessor y)))
(let ((p (make-instance 'a :x 1)))
  (change-class p 'b)
  (list (x p) (y p)))   ; interpreter: (1 2)   JVM: ArrayIndexOutOfBoundsException
```

Measure WASM P1 and component too. Fix the static `change-class` lowering (it likely
sizes the slot vector or maps slot indexes from the old class) on every backend that
fails; extend an existing ci-spec `change-class` case.
