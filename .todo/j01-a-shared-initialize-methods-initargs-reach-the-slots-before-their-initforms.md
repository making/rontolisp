# j01. A shared-initialize method's initargs reach the slots before their initforms run

Difficulty: High

typo stops in `vm/predicates.lisp` (measured 2026-11-07, interpreter; its lattice, trivia's
`(fail)` and `trivial-cltl2:function-information` no longer stop it): `update-fnrecord` makes a
`full-fnrecord`, whose slots say `:initform (alexandria:required-argument :min-arguments)` and whose
`shared-initialize` method computes the initargs and hands them on with `(apply #'call-next-method
instance slot-names :min-arguments ... initargs)`. Here every initform an initarg of the
`make-instance` call does not supply runs in the class's constructor (`%make-<name>`), before any
method, so `Required argument :MIN-ARGUMENTS missing.` is signalled; the standard `shared-initialize`
method fills a slot from the initargs IT receives and runs an initform only for a slot still unbound
(CLHS 7.1.4, 7.1.5).

SBCL 2.2.9 prints `(10 2)` and `(5 2)`; the interpreter and the JVM signal `a is required`, P1
traps on it uncaught:

    (defclass si-rec ()
      ((a :initarg :a :initform (error "a is required") :reader si-a)
       (b :initarg :b :initform 2 :reader si-b)))
    (defmethod shared-initialize ((instance si-rec) slot-names &rest initargs &key (a 10) &allow-other-keys)
      (apply #'call-next-method instance slot-names :a a initargs))
    (print (let ((r (make-instance 'si-rec))) (list (si-a r) (si-b r))))
    (print (let ((r (make-instance 'si-rec :a 5))) (list (si-a r) (si-b r))))

The lite model fills slots in the constructor and gives `shared-initialize` a system primary that
answers the instance (`.kb/clos.md`, "The instance-initialization protocol"). A class with an
`initialize-instance` or `shared-initialize` method needs allocation with unbound slots and a system
`shared-initialize` that fills from its own initargs, then the initforms of the slot-names still
unbound (`%mop-fill-slots` already does that half). Mind `:default-initargs`, class-allocated slots,
conditions, structs, `reinitialize-instance`, and the programs with no such method, whose
construction should stay what it is.

Done when: the program above prints SBCL's two lines on all four, and typo loads past
`vm/predicates.lisp` (petalisp is typo's consumer).
