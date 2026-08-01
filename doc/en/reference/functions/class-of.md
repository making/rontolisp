# class-of

`(class-of object)`

Returns the class METAOBJECT of any value -- the same memoized `standard-class` instance [`find-class`](find-class.md) answers, so `(eq (class-of x) (find-class 'name))` holds. A CLOS instance answers its class, a `defstruct` instance its structure type (as a `standard-class` instance too -- there is no `structure-class`), and every other value a slot-less built-in class named `integer`, `string`, `cons`, ..., with `t` for values outside that set (arrays included). Read the name with [`class-name`](class-name.md).

```lisp
(defclass point () ((x :initarg :x)))
(list (class-name (class-of 42))
      (class-name (class-of (make-instance 'point)))
      (eq (class-of 42) (find-class 'integer))) ; => (INTEGER POINT T)
```
