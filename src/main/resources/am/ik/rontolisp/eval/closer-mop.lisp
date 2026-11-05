;; The closer-mop package: a lite MOP shim satisfying the built-in ASDF system
;; "closer-mop" (a common :depends-on of JSON/serialization libraries such as
;; com.inuoe.jzon, and of postmodern's DAO layer). Two generations of slot
;; "metaobject" coexist here: the legacy (name declared-type) pair
;; %class-slot-defs returns from the class registry (what slot-walking
;; serializers handed a %class-designator TAG symbol consume), and the real
;; standard-class / standard-effective-slot-definition instances find-class
;; and class-of answer with (seeded layouts, see
;; ClosRegistry.ensureMopClassesSeeded).
;; Metaobject slots are read BY NAME (slot-value), not by %obj-ref index: a
;; user slot-definition class may inherit the seeded base through MULTIPLE
;; inheritance with its own mixin first (mito's table-column-class is
;; (column-slot-definitions c2mop:standard-direct-slot-definition)), which
;; puts the mixin's slots ahead of the base's in the layout, so no fixed
;; index is valid across subclasses -- only the slot NAMES are a contract.
;; Written in canonical (pre-resolved) shape like usocket.lisp; the package and
;; its nickname (c2mop) are seeded in PackageRegistry, as is the flat
;; closer-common-lisp (nickname c2cl) re-export package over cl + this one.

(defun closer-mop:classp (class) (typep class 'class))

(defun closer-mop:ensure-finalized (class) class)

(defun closer-mop:class-name (class) (slot-value class 'name))

(defun (setf closer-mop:class-name) (new-name class)
  ;; AMOP's (setf class-name): the metaobject's name, not the class table --
  ;; find-class keeps answering for the old name (contextl's layer-makunbound).
  (setf (slot-value class 'name) new-name))

(defun closer-mop:class-direct-superclasses (class)
  (slot-value class 'direct-superclasses))

(defun closer-mop:class-finalized-p (class) (slot-value class 'finalized-p))

(defmethod closer-mop:finalize-inheritance (class)
  ;; A class outside the metaclass protocol is finalized where it is defined, so
  ;; there is nothing left to do. The protocol (macro/mop-protocol.lisp) has the
  ;; method for a metaobject it builds.
  class)

(defun closer-mop:class-slots (class)
  (if (closer-mop:classp class)
      (slot-value class 'effective-slots)
      (%class-slot-defs class)))

(defmethod closer-mop:compute-slots (class)
  ;; Finalization is eager here (finalize-inheritance runs at definition time),
  ;; so the effective slots ARE the stored class-slots answer -- trivia level2's
  ;; find-effective-slot walks these with slot-definition-initargs/-name.
  (closer-mop:class-slots (closer-mop:ensure-finalized class)))

(defun closer-mop:generic-function-lambda-list (fn)
  ;; A generic function METAOBJECT (the generic-function protocol,
  ;; macro/generic-functions.lisp) carries its lambda list; a defgeneric's
  ;; dispatcher is a plain function value, with none to read. trivia level2's
  ;; (etypecase fn (generic-function ...)) guard routes only a metaobject here.
  (if (%obj-p fn)
      (slot-value fn 'lambda-list)
      (error "generic-function-lambda-list is not supported: ~S" fn)))

(defun closer-mop:slot-definition-name (slot)
  (if (%obj-p slot) (slot-value slot 'name) (car slot)))

(defun closer-mop:slot-definition-initargs (slot)
  (if (%obj-p slot) (slot-value slot 'initargs) nil))

(defun closer-mop:slot-definition-type (slot)
  (if (%obj-p slot) (slot-value slot 'type) (car (cdr slot))))

(defun closer-mop:slot-definition-readers (slot)
  (if (%obj-p slot) (slot-value slot 'readers) nil))

(defun closer-mop:slot-definition-writers (slot)
  ;; Index 6 (appended 2026-10-30): (setf accessor) and :writer names on a direct
  ;; slot definition; an effective one has none (AMOP 5.4.3).
  (if (%obj-p slot) (slot-value slot 'writers) nil))

(defun closer-mop:slot-definition-allocation (slot)
  ;; Index 7 (appended 2026-11-01): :class on a class-allocated slot; nil -- a
  ;; definition no :allocation initarg reached, a materialized instance slot --
  ;; is AMOP's default :instance.
  (let ((allocation (if (%obj-p slot) (slot-value slot 'allocation) nil)))
    (if allocation allocation :instance)))

;; Where an instance slot lives, for standard-instance-access (AMOP): the slot's
;; index in the instance, which a slot definition holds once its class has it among
;; its effective slots. A class-allocated slot and a direct definition have none.
(defun closer-mop:slot-definition-location (slot)
  (if (and (%obj-p slot)
           (member (slot-value slot 'allocation) '(nil :instance)))
      (slot-value slot '%location)
      nil))

(defun closer-mop::%instance-location (instance location)
  ;; AMOP leaves a bad argument undefined: a type-error on every backend, ahead of
  ;; what %obj-ref does with it on each. An index past the slots is not checked.
  (if (%obj-p instance)
      (if (and (integerp location) (>= location 0))
          location
          (error 'type-error
                 :datum location
                 :expected-type 'unsigned-byte
                 :format-control
                 "STANDARD-INSTANCE-ACCESS: The value ~S is not a slot location"
                 :format-arguments (list location)))
      (error 'type-error
             :datum instance
             :expected-type 'standard-object
             :format-control
             "STANDARD-INSTANCE-ACCESS: The value ~S is not an instance"
             :format-arguments (list instance))))

(defun closer-mop:standard-instance-access (instance location)
  (let ((index (closer-mop::%instance-location instance location)))
    (%obj-ref instance index)))

(defun (setf closer-mop:standard-instance-access) (new-value instance location)
  (let ((index (closer-mop::%instance-location instance location)))
    (%obj-set instance index new-value)))

(defun closer-mop:funcallable-standard-instance-access (instance location)
  (closer-mop:standard-instance-access instance location))

(defun (setf closer-mop:funcallable-standard-instance-access)
    (new-value instance location)
  (setf (closer-mop:standard-instance-access instance location) new-value))

(defun closer-mop:slot-definition-initform (slot)
  (if (%obj-p slot) (slot-value slot 'initform) nil))

(defun closer-mop:slot-definition-initfunction (slot)
  ;; The initfunction slot (appended 2026-08-03): a live (lambda () initform)
  ;; thunk on DRIVER-built definitions (a :metaclass class's canonicalized
  ;; specs carry one); nil on materialized plain views and on slots with no
  ;; :initform -- mito's migration diffing branches on exactly that truthiness.
  (if (%obj-p slot) (slot-value slot 'initfunction) nil))

(defun closer-mop:class-direct-slots (class)
  ;; Direct-slot-definition metaobjects: the driver builds them for a :metaclass
  ;; class, the materialized plain view from the registry's direct specs (nil for a
  ;; struct or a built-in class).
  (slot-value class 'direct-slots))

(defun closer-mop:class-direct-subclasses (class)
  (%class-direct-subclasses class))

(defun closer-mop:class-default-initargs (class)
  ;; AMOP: (initarg form function) for the class and its superclasses, the most
  ;; specific default of an initarg winning.
  (%class-default-initargs class))

(defun closer-mop:subclassp (class superclass)
  ;; Whether SUPERCLASS is in CLASS's precedence list (reflexive); each argument
  ;; a metaobject or a class name. The class lattice is what subtypep consults.
  (if (subtypep class superclass) t nil))

;; One prototype per class (AMOP class-prototype), so (eq (class-prototype c)
;; (class-prototype c)) holds -- contextl caches layer contexts by it.
(defvar closer-mop::*class-prototypes* nil)

;; A built-in class has no instances to allocate: its prototype is a value of it,
;; SBCL's where the value is a constant.
(defvar closer-mop::*built-in-prototype-classes*
  '(t integer number real rational ratio float complex string character symbol
      keyword null boolean cons list sequence vector array bit-vector hash-table
      function stream pathname))

(defun closer-mop::%built-in-prototype (name)
  (case name
    ((integer number real rational) 0)
    (ratio 1/42)
    (float 0.0)
    (complex (complex 0 1))
    (string "")
    (character #\*)
    (symbol '*)
    (keyword :keyword)
    ((null list) nil)
    (cons (list nil))
    ((sequence vector) (vector))
    (array (make-array nil))
    (bit-vector (make-array 0 :element-type 'bit))
    (hash-table (make-hash-table))
    (function #'identity)
    (stream *standard-output*)
    (pathname (pathname ""))
    (t t)))

(defun closer-mop:class-prototype (class)
  (let ((hit (assoc class closer-mop::*class-prototypes* :test #'eq)))
    (if hit
        (cdr hit)
        (let ((prototype
               (if (member (slot-value class 'name)
                           closer-mop::*built-in-prototype-classes*)
                   (closer-mop::%built-in-prototype (slot-value class 'name))
                   (allocate-instance class))))
          (setq closer-mop::*class-prototypes*
                (cons (cons class prototype) closer-mop::*class-prototypes*))
          prototype))))

(defun closer-mop:required-args (lambda-list &optional (collector #'identity))
  ;; The parameters ahead of the first lambda-list keyword, each passed through
  ;; COLLECTOR.
  (let ((out nil))
    (dolist (arg lambda-list)
      (if (member arg lambda-list-keywords)
          (return)
          (setq out (cons (funcall collector arg) out))))
    (reverse out)))

(defun closer-mop::%ensure-class-form (name initargs)
  ;; The defclass of ENSURE-CLASS's initargs: a superclass by name, a slot from its
  ;; canonical plist, any other initarg as a class option whose tail is its value.
  (let ((supers nil) (slots nil) (options nil) (cursor initargs))
    (loop while cursor
          do
            (let ((key (car cursor)) (value (car (cdr cursor))))
              (cond ((eq key :name) nil)
                    ((eq key :direct-superclasses)
                     (setq supers
                           (mapcar (lambda (super)
                                     (if (symbolp super)
                                         super
                                         (slot-value super 'name))) value)))
                    ((eq key :direct-slots)
                     (setq slots (mapcar #'closer-mop::%slot-spec-form value)))
                    ((eq key :metaclass)
                     (setq options (cons (list :metaclass value) options)))
                    (t (setq options (cons (cons key value) options))))
              (setq cursor (cdr (cdr cursor)))))
    (append (list 'defclass name supers slots) (reverse options))))

(defun closer-mop::%slot-spec-form (plist)
  ;; AMOP's canonicalized slot specification back in defclass's spelling.
  (let ((spec nil) (cursor plist))
    (loop while cursor
          do
            (let ((key (car cursor)) (value (car (cdr cursor))))
              (cond ((eq key :name) nil)
                    ((eq key :initargs)
                     (dolist (initarg value)
                       (setq spec (cons initarg (cons :initarg spec)))))
                    ((eq key :readers)
                     (dolist (reader value)
                       (setq spec (cons reader (cons :reader spec)))))
                    ((eq key :writers)
                     (dolist (writer value)
                       (setq spec (cons writer (cons :writer spec)))))
                    ((eq key :initfunction) nil)
                    (t (setq spec (cons value (cons key spec)))))
              (setq cursor (cdr (cdr cursor)))))
    (cons (getf plist :name) (reverse spec))))

(defun closer-mop:ensure-class (name &rest initargs)
  ;; AMOP: defines the class, or defines it again. As a defclass, so that a method
  ;; can specialize on it and make-instance find it by name.
  (eval (closer-mop::%ensure-class-form name initargs))
  (find-class name))

(defun closer-mop:extract-specializer-names (specialized-lambda-list)
  ;; AMOP: one name per required parameter, t for an unspecialized one.
  (closer-mop:required-args specialized-lambda-list
   (lambda (arg) (if (and (consp arg) (cdr arg)) (car (cdr arg)) t))))

(defun closer-mop:extract-lambda-list (specialized-lambda-list)
  ;; AMOP: the list with its required parameters' specializers removed.
  (let ((required
         (closer-mop:required-args specialized-lambda-list
          (lambda (arg) (if (consp arg) (car arg) arg)))))
    (append required (nthcdr (length required) specialized-lambda-list))))

(defun closer-mop:set-funcallable-instance-function (instance function)
  ;; The function a funcallable instance calls lives in the cell its layout
  ;; reserves before the tail (LispLayout.FUNCALLABLE_CELL, -2 from the end of
  ;; the storage), where every backend's call through a value reads it. A
  ;; funcallable instance handed over as the function is wrapped, so the cell
  ;; always holds a plain function.
  (if (typep instance 'closer-mop:funcallable-standard-object)
      (if (functionp function)
          (%obj-set instance -2
                    (if (%obj-p function)
                        (lambda (&rest args) (apply function args))
                        function))
          (error 'type-error
                 :datum function
                 :expected-type 'function
                 :format-control
                 "SET-FUNCALLABLE-INSTANCE-FUNCTION: The value ~S is not of type FUNCTION"
                 :format-arguments (list function)))
      (error 'type-error
             :datum instance
             :expected-type 'closer-mop:funcallable-standard-object
             :format-control
             "SET-FUNCALLABLE-INSTANCE-FUNCTION: The value ~S is not a funcallable instance"
             :format-arguments (list instance))))
