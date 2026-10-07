# class-of

`(class-of object)`

Returns the class METAOBJECT of any value -- the same memoized `standard-class` instance [`find-class`](find-class.md) answers, so `(eq (class-of x) (find-class 'name))` holds. A CLOS instance answers its class, a `defstruct` instance its structure type (as a `standard-class` instance too -- there is no `structure-class`), and every other value a slot-less built-in class named `integer`, `string`, `cons`, ..., with `t` for values outside that set. An array answers the narrowest built-in class that covers it: `string` for a string, `vector` for any other rank-1 array, `array` above and below that rank. A built-in stream answers the slot-less class of the standard name [`type-of`](type-of.md) gives it (`string-stream`, `two-way-stream`, ..., `stream`). Read the name with [`class-name`](class-name.md); [`type-of`](type-of.md) is a different, finer view and is unaffected.

```lisp
(defclass point () ((x :initarg :x)))
(list (class-name (class-of 42))
      (class-name (class-of (make-instance 'point)))
      (eq (class-of 42) (find-class 'integer))) ; => (INTEGER POINT T)
```

```lisp
(list (class-name (class-of (make-array 3)))
      (class-name (class-of #2a((1 2) (3 4))))
      (class-name (class-of "ab"))) ; => (VECTOR ARRAY STRING)
```

```lisp
(let ((s (make-string-input-stream "a")))
  (list (class-name (class-of s))
        (eq (class-of s) (find-class 'string-stream)))) ; => (STRING-STREAM T)
```
