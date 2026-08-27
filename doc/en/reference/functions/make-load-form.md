# make-load-form

`(make-load-form object &optional environment)`

The generic function the COMPILER consults when an object appears as a literal in code it is compiling. Its method returns a form that reconstructs the object, and that form replaces the object in the compiled program.

An object gets into code that way when a macro splices a live object into its own expansion — the standard reason a library needs this. CFFI does it in every `defcfun` whose argument or return type is a `defcenum`, and declares a method so the object survives compilation.

Like [`print-object`](print-object.md) there is no system method: the compile path routes only a type some method specializes on. A type no method covers is dumped as it always was, by writing out its slots, so a program that defines no `make-load-form` method compiles exactly as before. The two-value form is honored: a method may return a creation form and an init form, and the init form runs against the freshly created object.

The creation form is evaluated **once per program run**, not once per use, so a method whose form is expensive (parsing a foreign type, compiling a scanner) costs that work one time.

```lisp
(defstruct mlf-pt x y)
(defmethod make-load-form ((p mlf-pt) &optional env)
  (declare (ignore env))
  (list 'make-mlf-pt :x (mlf-pt-x p) :y (mlf-pt-y p)))
(make-load-form (make-mlf-pt :x 1 :y 2))
; => (MAKE-MLF-PT :X 1 :Y 2)
```

The method is what lets an object with a slot the compiler cannot write out — a hash table, a foreign pointer — reach a compiled program at all. Without one such a literal fails the compile with `Cannot quote: ...`, which is the signal that the type wants a method.

```lisp
(defclass mlf-box () ((name :initarg :name :accessor mlf-box-name) (cache :initarg :cache)))
(defmethod make-load-form ((b mlf-box) &optional env)
  (declare (ignore env))
  (list 'make-instance ''mlf-box :name (mlf-box-name b)))
(defparameter *mlf-box* (make-instance 'mlf-box :name "dumped" :cache (make-hash-table)))
(defmacro mlf-splice-box () *mlf-box*)
(mlf-box-name (mlf-splice-box))
; => "dumped"
```

The interpreter never needs any of this — its literal IS the live object — so the method only changes what a compiled program does, and the two agree by construction.

Lite: an object reached only through a quoted ARRAY or a hash table inside a constant is not rebuilt (a quoted cons structure is). The creation form may not refer back to the object being created.
