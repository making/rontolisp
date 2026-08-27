# make-load-form-saving-slots

`(make-load-form-saving-slots object &key slot-names environment)`

The ready-made [`make-load-form`](make-load-form.md) answer: a form that rebuilds the object with its slot values as they are now. A method that has nothing special to say delegates to it, which is what cl-ppcre's `charmap` and `charset` methods do.

The form it returns is the internal instance constructor — the same one the compiler writes out for an instance with no method at all — so delegating to it asks for the built-in behavior explicitly rather than changing it.

```lisp
(defstruct mlfss-pt x y)
(make-load-form-saving-slots (make-mlfss-pt :x 1 :y 2))
; => (%OBJ-NEW (QUOTE %struct-MLFSS-PT) (QUOTE 1) (QUOTE 2))
```

```lisp
(defstruct mlfss2-pt x y)
(defmethod make-load-form ((p mlfss2-pt) &optional env)
  (make-load-form-saving-slots p :environment env))
(defparameter *mlfss2* (make-mlfss2-pt :x 3 :y "four"))
(defmacro mlfss2-splice () *mlfss2*)
(list (mlfss2-pt-x (mlfss2-splice)) (mlfss2-pt-y (mlfss2-splice)))
; => (3 "four")
```

Lite: `:slot-names` is ignored — every slot travels — and `:environment` is accepted and unused. The object is rebuilt in one step rather than allocated and then filled, so it cannot carry a reference back to itself. A slot holding something the compiler cannot write out (a hash table, a stream) still fails the compile; a method that must skip such a slot builds its own form instead.
