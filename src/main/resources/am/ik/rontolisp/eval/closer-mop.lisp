;; The closer-mop package: a lite MOP shim satisfying the built-in ASDF system
;; "closer-mop" (a common :depends-on of JSON/serialization libraries such as
;; com.inuoe.jzon, and of postmodern's DAO layer). Two generations of slot
;; "metaobject" coexist here: the legacy (name declared-type) pair
;; %class-slot-defs returns from the class registry (what slot-walking
;; serializers handed a class-of TAG symbol consume), and the real
;; standard-class / standard-effective-slot-definition instances find-class
;; answers with (seeded layouts, see ClosRegistry.seedMopClasses; the %obj-ref
;; indexes below are that seeding's documented order contract).
;; Written in canonical (pre-resolved) shape like usocket.lisp; the package and
;; its nicknames (c2mop, c2cl) are seeded in PackageRegistry.

(defun closer-mop:classp (class)
  (typep class 'standard-class))

(defun closer-mop:ensure-finalized (class)
  class)

(defun closer-mop:class-name (class)
  (%obj-ref class 0))

(defun closer-mop:class-direct-superclasses (class)
  (%obj-ref class 1))

(defun closer-mop:class-finalized-p (class)
  (%obj-ref class 4))

(defun closer-mop:class-slots (class)
  (if (closer-mop:classp class)
      (%obj-ref class 3)
      (%class-slot-defs class)))

(defun closer-mop:slot-definition-name (slot)
  (if (%obj-p slot)
      (%obj-ref slot 0)
      (car slot)))

(defun closer-mop:slot-definition-initargs (slot)
  (if (%obj-p slot)
      (%obj-ref slot 1)
      nil))

(defun closer-mop:slot-definition-type (slot)
  (if (%obj-p slot)
      (%obj-ref slot 3)
      (car (cdr slot))))
