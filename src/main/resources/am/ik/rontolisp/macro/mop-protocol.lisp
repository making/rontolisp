;; The metaclass-protocol runtime: the system default methods of the MOP
;; class-definition generics and the %ensure-class-with-metaclass driver a
;; (defclass ... (:metaclass M)) expansion calls at definition time. Injected
;; ONLY into programs that carry a :metaclass defclass (LispMacroExpander gate;
;; the interpreter evaluates the same forms once when it first meets one), so
;; every other program -- closer-mop users included -- stays byte-identical.
;;
;; Written in canonical (pre-resolved) shape like closer-mop.lisp, and
;; deliberately SELF-CONTAINED: metaobject slots are read/written through the
;; seeded %obj-ref index contract (standard-class: 0 name, 1 direct-superclasses,
;; 2 direct-slots, 3 effective-slots, 4 finalized-p; slot definitions: 0 name,
;; 1 initargs, 2 initform, 3 type, 4 readers -- see
;; ClosRegistry.ensureMopClassesSeeded), never through the closer-mop shim
;; defuns, so the protocol works whether or not the shim library is loaded.
;;
;; The class arguments of %mop-make-instance are DESIGNATORS (a metaobject or a
;; plain name symbol): the default direct/effective-slot-definition-class
;; methods answer the NAME, so the defaults drag no find-class runtime into the
;; program; a user method (postmodern returns (find-class 'direct-column-slot))
;; may answer the metaobject.

(defmethod closer-mop:validate-superclass (class superclass)
  ;; Permissive by design (lite divergence): CL's cross-metaclass rejection is
  ;; exactly what user methods exist to relax, and the static subset has no
  ;; incompatible-layout case of its own to guard.
  t)

(defmethod closer-mop:direct-slot-definition-class (class &rest initargs)
  'standard-direct-slot-definition)

(defmethod closer-mop:effective-slot-definition-class (class &rest initargs)
  'standard-effective-slot-definition)

(defmethod closer-mop:compute-effective-slot-definition (class name direct-slot-definitions)
  ;; The effective-slot-definition-class call AND the instantiation both happen
  ;; here, INSIDE the dynamic extent of a user override's call-next-method --
  ;; the contract postmodern's *direct-column-slot* binding (and the
  ;; :initform *direct-column-slot* of its effective slot class) relies on.
  (let ((dsd (car direct-slot-definitions)))
    (%mop-make-instance (closer-mop:effective-slot-definition-class class :name name)
                        :name name
                        :initargs (%obj-ref dsd 1)
                        :initform (%obj-ref dsd 2)
                        :type (%obj-ref dsd 3)
                        :readers (%obj-ref dsd 4))))

(defmethod closer-mop:finalize-inheritance (class)
  ;; Effective slots in static-layout order: the superclass's effective slots
  ;; first (recomputed through the protocol when an own direct slot shadows
  ;; one, reused as-is otherwise -- a lite divergence: the direct-definition
  ;; list handed to compute-effective-slot-definition is the shadowing
  ;; definition alone), then the own slots that introduce new names.
  (let* ((super (car (%obj-ref class 1)))
         (direct-slots (%obj-ref class 2))
         (inherited (if super (%obj-ref super 3) nil))
         (effective nil))
    (dolist (eslot inherited)
      (let ((own (%mop-find-slot-definition (%obj-ref eslot 0) direct-slots)))
        (setq effective
              (cons (if own
                        (closer-mop:compute-effective-slot-definition class (%obj-ref eslot 0) (cons own nil))
                        eslot)
                    effective))))
    (dolist (dsd direct-slots)
      (if (%mop-find-slot-definition (%obj-ref dsd 0) inherited)
          nil
          (setq effective
                (cons (closer-mop:compute-effective-slot-definition class (%obj-ref dsd 0) (cons dsd nil))
                      effective))))
    (%obj-set class 3 (reverse effective))
    (%obj-set class 4 t)
    class))

(defun %mop-find-slot-definition (name slot-definitions)
  (let ((hit nil))
    (dolist (s slot-definitions)
      (if (eq (%obj-ref s 0) name)
          (setq hit s)
          nil))
    hit))

(defun %ensure-class-with-metaclass (name metaclass supers slot-specs class-initargs)
  ;; The definition-time driver: instantiate the metaclass (the user's
  ;; shared-initialize hooks run with the canonicalized slot-spec plists as the
  ;; :direct-slots initarg, per AMOP), validate the superclasses, build the
  ;; direct-slot-definition metaobjects through the protocol, finalize
  ;; EAGERLY (a documented divergence -- inputs are static, so only the timing
  ;; of definition errors moves), and prime the find-class/class-of memo.
  (let* ((super-metaobjects (mapcar #'find-class supers))
         (class (apply #'%mop-make-instance metaclass
                       :name name
                       :direct-superclasses super-metaobjects
                       :direct-slots slot-specs
                       class-initargs)))
    (dolist (super super-metaobjects)
      (if (closer-mop:validate-superclass class super)
          nil
          (error "The class ~A cannot be a superclass of the ~A class ~A"
                 (%obj-ref super 0) metaclass name)))
    (let ((direct-slots nil))
      (dolist (spec slot-specs)
        (setq direct-slots
              (cons (apply #'%mop-make-instance
                           (apply #'closer-mop:direct-slot-definition-class class spec)
                           spec)
                    direct-slots)))
      (%obj-set class 2 (reverse direct-slots)))
    (closer-mop:finalize-inheritance class)
    (%register-class-metaobject name class)
    class))
