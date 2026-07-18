;; The closer-mop package: a lite MOP shim satisfying the built-in ASDF system
;; "closer-mop" (a common :depends-on of JSON/serialization libraries such as
;; com.inuoe.jzon). rontolisp's CLOS subset has no slot metaobjects, so class
;; introspection yields no slots: a library that walks c2mop:class-slots to
;; serialize an arbitrary CLOS instance sees an object with no fields. The
;; accessors keep the (name . type) pair shape those walkers construct.
;; Written in canonical (pre-resolved) shape like usocket.lisp; the package and
;; its nicknames (c2mop, c2cl) are seeded in PackageRegistry.

(defun closer-mop:ensure-finalized (class)
  class)

(defun closer-mop:class-slots (class)
  (declare (ignore class))
  nil)

(defun closer-mop:slot-definition-name (slot)
  (car slot))

(defun closer-mop:slot-definition-type (slot)
  (car (cdr slot)))
