# defclass

`(defclass name (superclass?) ((slot slot-option...) ...) class-option...)`

Defines a class and returns the name symbol. This is a **static CLOS subset**: at most one superclass (single inheritance), and instances are first-class objects created with [`make-instance`](../macros/make-instance.md) (like [`defstruct`](defstruct.md) instances they are not lists — `consp` is `nil` — and `print` shows them as `#<NAME :SLOT value ...>`). Slot options:

- `:initarg keyword` — the constructor keyword for the slot (defaults to the slot-name keyword)
- `:initform expr` — the default value, evaluated at construction time when the slot is not supplied. **Omitting it leaves the slot UNBOUND**, as in CL: [`slot-boundp`](../macros/slot-boundp.md) is `nil` and a read signals `unbound-slot`
- `:reader fn` — defines `fn` as a reader function
- `:accessor fn` — like `:reader`, and additionally a `setf`-able place

A subclass inherits every slot of its superclass and its instances match the superclass's [`defmethod`](defmethod.md) class specializers. A subclass may re-declare an inherited slot: the storage stays the one inherited slot, while the subclass's `:initform`/`:initarg` override the inherited ones and its readers/accessors are added to them. Reader/accessor functions are ordinary defuns, so they are first-class. The class options `(:documentation "...")` (accepted and ignored) and `(:default-initargs :initarg value ...)` (defaults applied by [`make-instance`](../macros/make-instance.md) for initargs not supplied) are supported, and the slot option `:type` is recorded (no checking); other class options, other slot options (`:allocation`, `:writer`, ...), and multiple inheritance are errors. On the compilation path `defclass` is only supported as a top-level form; of the runtime class operations only [`change-class`](../macros/change-class.md) exists (with a literal target class), not `find-class` or class redefinition.

```lisp
(defclass animal () ((name :initarg :name :accessor animal-name)))
(defclass dog (animal) ((breed :initarg :breed :initform "mixed" :reader dog-breed)))
(setq d (make-instance 'dog :name "Rex"))
(list (animal-name d) (dog-breed d)) ; => ("Rex" "mixed")
```

```lisp
(defclass shape () ((sides :initform 0 :reader sides) (label :initarg :label)))
(defclass square (shape) ((sides :initform 4 :accessor square-sides)))
(let ((s (make-instance 'square)))
  (list (sides s) (square-sides s) (slot-boundp s 'label))) ; => (4 4 NIL)
```

```lisp
(defclass counter () ((n :initform 0 :accessor counter-n)))
(setq c (make-instance 'counter))
(incf (counter-n c))
(setf (counter-n c) (+ (counter-n c) 10))
(counter-n c) ; => 11
```
