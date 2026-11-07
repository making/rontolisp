# make-instance

`(make-instance 'class-name :initarg value ...)`

Creates an instance of a [`defclass`](../special-forms/defclass.md) class. Slots are supplied by their `:initarg` keywords (defaulting to the slot-name keyword); an unsupplied slot takes its `:initform` (or `nil`). An initarg no slot declares is accepted, so one an `initialize-instance` method takes as a keyword reaches that method, whether or not the class has slots. A **literal quoted class name** naming a class defined by `defclass` compiles to a direct constructor call. A class that a primary, `:before` or `:around` method of `initialize-instance` or `shared-initialize` applies to is initialized as CLHS 7.1 describes instead: its slots start unbound, and the system `shared-initialize` method fills them from the initargs that reach it (a method may pass others), then fills each slot still unbound from its `:initform`; there only an initarg the slot declares fills it. A COMPUTED class also works — a name symbol built at run time (`(make-instance (intern (format nil "~A-~A" style '#:reporter) package) ...)`, matched under either the `pkg:name` or the `pkg::name` spelling, so the class need not be exported) or the metaobject [`find-class`](../functions/find-class.md) answers — and so does `#'make-instance` as a value; both dispatch at run time over the classes the program defines. On the compiled backends that SET is fixed at compile time: a class built from runtime data does not exist.

```lisp
(defclass point () ((x :initarg :x :initform 0 :reader point-x)
                    (y :initarg :y :initform 0 :reader point-y)))
(setq p (make-instance 'point :x 3))
(list (point-x p) (point-y p)) ; => (3 0)
```
