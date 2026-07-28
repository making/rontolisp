# with-accessors

`(with-accessors ((var accessor)...) instance body...)`

Binds each `var` as a symbol-macro-style place standing for `(accessor instance)` in the body -- the accessor-call twin of [`with-slots`](with-slots.md). The instance form is evaluated once. Reads call the accessor, and [`setf`](setf.md)/`push`/`incf` of a bound name writes through the accessor's `setf` place, so a class that exposes only accessors needs no `slot-value`.

Lite: the substitution is textual over the body (quoted data is skipped); an inner binding shadowing one of the names is still substituted.

```lisp
(defclass wa-point () ((x :initarg :x :accessor wa-x) (y :initarg :y :accessor wa-y)))
(let ((p (make-instance 'wa-point :x 3 :y 4)))
  (with-accessors ((x wa-x) (y wa-y)) p
    (setf x (+ x y))
    (list x y))) ; => (7 4)
```
