;; The closer-mop package: a lite MOP shim satisfying the built-in ASDF system
;; "closer-mop" (a common :depends-on of JSON/serialization libraries such as
;; com.inuoe.jzon). rontolisp's CLOS subset has no slot metaobjects; a slot
;; "metaobject" here is the (name declared-type) pair %class-slot-defs returns
;; from the class registry, which is exactly the shape slot-walking serializers
;; consume (name + type + slot-value by name).
;; Written in canonical (pre-resolved) shape like usocket.lisp; the package and
;; its nicknames (c2mop, c2cl) are seeded in PackageRegistry.

(defun closer-mop:ensure-finalized (class)
  class)

(defun closer-mop:class-slots (class)
  (%class-slot-defs class))

(defun closer-mop:slot-definition-name (slot)
  (car slot))

(defun closer-mop:slot-definition-type (slot)
  (car (cdr slot)))
