# defclass

`(defclass name (superclass*) ((slot slot-option...) ...) class-option...)`

Defines a class and returns the name symbol. This is a **static CLOS subset**: every superclass must be defined by an earlier `defclass`, and instances are first-class objects created with [`make-instance`](../macros/make-instance.md) (like [`defstruct`](defstruct.md) instances they are not lists — `consp` is `nil` — and `print` shows them as `#<NAME :SLOT value ...>`). Slot options:

- `:initarg keyword` — the constructor keyword for the slot (defaults to the slot-name keyword)
- `:initform expr` — the default value, evaluated at construction time when the slot is not supplied. **Omitting it leaves the slot UNBOUND**, as in CL: [`slot-boundp`](../macros/slot-boundp.md) is `nil` and a read signals `unbound-slot`
- `:reader fn` — defines `fn` as a reader function
- `:accessor fn` — like `:reader`, and additionally a `setf`-able place

A subclass inherits every slot of its superclasses and its instances match the superclasses' [`defmethod`](defmethod.md) class specializers. **Multiple inheritance** is supported: the first superclass's slots come first and each later superclass appends the slots whose name is not present yet, a diamond keeps ONE copy of the shared slot, and both method dispatch and re-declared slot options follow the **class precedence list** (CLHS 4.3.5 -- the class, then its superclasses left to right, each before its own superclasses; conflicting local orders are an error). A subclass may re-declare an inherited slot: the storage stays the one inherited slot, while the subclass's `:initform`/`:initarg` override the inherited ones and its readers/accessors are added to them. Reader/accessor functions are ordinary defuns, so they are first-class. The class options `(:documentation "...")` (accepted and ignored) and `(:default-initargs :initarg value ...)` (defaults applied by [`make-instance`](../macros/make-instance.md) for initargs not supplied) are supported, and the slot option `:type` is recorded (no checking); without a `:metaclass` (below), other class options and other slot options (`:allocation`, `:writer`, ...) are errors. On the compilation path `defclass` is only supported as a top-level form; [`find-class`](../functions/find-class.md) and [`class-of`](../functions/class-of.md) answer the class metaobject, and of the runtime class operations only [`change-class`](../macros/change-class.md) exists (with a literal target class) — no class redefinition.

**Metaclasses** (the static MOP subset): the class option `(:metaclass M)` names a class inheriting `standard-class`, defined earlier by `defclass`. The class definition then runs the class-definition protocol at **definition time**: the metaclass is instantiated — its `shared-initialize` methods see every other unknown class option as an initarg whose value is the option's tail list, and a `:before` method's write to a slot with a declared `:initarg` is overwritten by the supplied initarg afterwards, CL's fill order (a slot without a declared `:initarg`, like `table-name` below, keeps the `:before`'s write) — each slot's non-standard options (`:col-type`, ...) are handed to `closer-mop:direct-slot-definition-class` as initargs and its answer is instantiated as that slot's direct-slot-definition metaobject, effective slots are computed through `closer-mop:compute-effective-slot-definition` (the default method picks and instantiates `closer-mop:effective-slot-definition-class` inside the dynamic extent of a user override's `call-next-method`), and `closer-mop:finalize-inheritance` runs **eagerly** (CL finalizes lazily; inputs are static, so only the timing of definition errors differs). `find-class` and `class-of` answer the metaclass instance from then on, while instances of the class itself stay ordinary objects. The protocol is static: a `defclass` in a non-top-level position, or protocol calls on classes unknown at definition time, signal an error.

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
(defclass fast-stream () ((buffer :initform nil :accessor stream-buffer)))
(defclass output-mixin () ((pos :initform 0 :accessor stream-pos)))
(defclass fast-output-stream (fast-stream output-mixin) ())
(defgeneric stream-kind (s))
(defmethod stream-kind ((s fast-stream)) :fast)
(defmethod stream-kind ((s output-mixin)) :output)
(let ((s (make-instance 'fast-output-stream)))
  (setf (stream-pos s) 10)
  (list (stream-kind s) (stream-pos s) (typep s 'output-mixin))) ; => (:FAST 10 T)
```

```lisp
(defclass counter () ((n :initform 0 :accessor counter-n)))
(setq c (make-instance 'counter))
(incf (counter-n c))
(setf (counter-n c) (+ (counter-n c) 10))
(counter-n c) ; => 11
```

```lisp
(defclass table-class (standard-class) ((table-name)))
(defmethod closer-mop:validate-superclass ((c table-class) (s standard-class)) t)
(defmethod shared-initialize :before ((c table-class) slots &key table-name &allow-other-keys)
  (if table-name (setf (slot-value c 'table-name) (car table-name)) nil))
(defclass account () ((id :initarg :id)) (:metaclass table-class) (:table-name "accounts"))
(list (class-name (find-class 'account))
      (slot-value (find-class 'account) 'table-name)
      (typep (find-class 'account) 'table-class)) ; => (ACCOUNT "accounts" T)
```
