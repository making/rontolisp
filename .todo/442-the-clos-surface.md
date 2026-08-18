# 442. The CLOS surface: `reinitialize-instance`, computed `change-class`, two slot options, metaobject types

Difficulty: High

Child of `.todo/436` (read it first). Wave 1. Five gaps, all of them ordinary
CLOS that upstream ASDF uses as a matter of course.

## The defects

```lisp
;; 1. registered in CL_SYMBOLS, not implemented
(defclass p () ((n :initarg :n :reader n)))
(let ((o (make-instance 'p :n 1))) (reinitialize-instance o :n 2) (n o))
;; => The function REINITIALIZE-INSTANCE is undefined

;; 2. change-class demands a literal quoted class name
(defclass base () ((n :initarg :n))) (defclass sub (base) ())
(let ((o (make-instance 'base :n 1)) (cls 'sub)) (change-class o cls))
;; => CHANGE-CLASS requires a literal quoted class name

;; 3. / 4. defclass slot options
(defclass q () ((a :writer (setf q-a) :initform 1)))   ; => :WRITER is not supported
(defclass r () ((a :initform 1 :allocation :class)))   ; => :ALLOCATION is not supported

;; 5. metaobject type specifiers
(defclass s1 () ())
(typecase (find-class 's1) (standard-class :meta) (t :other))
;; => Unsupported type specifier: STANDARD-CLASS
```

## Order

Do 1 first. `shared-initialize` and `slot-makunbound` are registered-and-absent
in the same way, so grep for the rest of that class of hole and report what you
find. Once the initialization protocol is re-entrant, 2-5 should be small.

Read `.kb/clos.md` (initialization protocol, `:around` composition,
`change-class`'s initform path, the shape of the static registry) and
`.kb/instance-syntax.md`.

## A hint, not an implementation

The spike stood `reinitialize-instance` up in Lisp over `c2mop:class-slots` and
`c2mop:slot-definition-initargs` (both work once `(asdf:load-system
:closer-mop)` has run) to get past it. Useful for the shape; it skips
`shared-initialize` and the `:after` methods, so it is not the answer.

## Acceptance

All five snippets behave as CL specifies on all four backends; ci-spec cases
(`clos-reinitialize-442`, `clos-computed-change-class-442`).
