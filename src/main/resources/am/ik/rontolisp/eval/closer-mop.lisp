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

(defun closer-mop:classp (class) (typep class 'standard-class))

(defun closer-mop:ensure-finalized (class) class)

(defun closer-mop:class-name (class) (slot-value class 'name))

(defun closer-mop:class-direct-superclasses (class)
  (slot-value class 'direct-superclasses))

(defun closer-mop:class-finalized-p (class) (slot-value class 'finalized-p))

(defun closer-mop:class-slots (class)
  (if (closer-mop:classp class)
      (slot-value class 'effective-slots)
      (%class-slot-defs class)))

(defun closer-mop:compute-slots (class)
  ;; Finalization is eager here (finalize-inheritance runs at definition time),
  ;; so the effective slots ARE the stored class-slots answer -- trivia level2's
  ;; find-effective-slot walks these with slot-definition-initargs/-name.
  (closer-mop:class-slots (closer-mop:ensure-finalized class)))

(defun closer-mop:generic-function-lambda-list (fn)
  ;; A defgeneric's dispatcher is a plain function value on every backend; no
  ;; metaobject exists to read a lambda list from. Reachable only through an
  ;; explicit call -- the generic-function TYPE is empty, so trivia level2's
  ;; (etypecase fn (generic-function ...)) guard never routes here.
  (error "generic-function-lambda-list is not supported: ~S" fn))

(defun closer-mop:slot-definition-name (slot)
  (if (%obj-p slot) (slot-value slot 'name) (car slot)))

(defun closer-mop:slot-definition-initargs (slot)
  (if (%obj-p slot) (slot-value slot 'initargs) nil))

(defun closer-mop:slot-definition-type (slot)
  (if (%obj-p slot) (slot-value slot 'type) (car (cdr slot))))

(defun closer-mop:slot-definition-readers (slot)
  (if (%obj-p slot) (slot-value slot 'readers) nil))

(defun closer-mop:slot-definition-initfunction (slot)
  ;; The initfunction slot (appended 2026-08-03): a live (lambda () initform)
  ;; thunk on DRIVER-built definitions (a :metaclass class's canonicalized
  ;; specs carry one); nil on materialized plain views and on slots with no
  ;; :initform -- mito's migration diffing branches on exactly that truthiness.
  (if (%obj-p slot) (slot-value slot 'initfunction) nil))

(defun closer-mop:class-direct-slots (class)
  ;; Direct-slot-definition metaobjects; nil on a materialized plain view (the
  ;; static registry keeps direct specs, but only the metaclass driver builds
  ;; direct-slot metaobjects).
  (slot-value class 'direct-slots))

(defun closer-mop:class-direct-subclasses (class)
  (%class-direct-subclasses class))
