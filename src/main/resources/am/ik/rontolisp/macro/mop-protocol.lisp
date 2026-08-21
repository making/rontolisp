;; The metaclass-protocol runtime: the system default methods of the MOP
;; class-definition generics and the %ensure-class-with-metaclass driver a
;; (defclass ... (:metaclass M)) expansion calls at definition time. Injected
;; ONLY into programs that carry a :metaclass defclass (LispMacroExpander gate;
;; the interpreter evaluates the same forms once when it first meets one), so
;; every other program -- closer-mop users included -- stays byte-identical.
;;
;; Written in canonical (pre-resolved) shape like closer-mop.lisp, and
;; deliberately SELF-CONTAINED: metaobject slots are read/written BY NAME
;; through slot-value (a CL builtin, never the closer-mop shim defuns, so the
;; protocol works whether or not the shim library is loaded). By name and NOT
;; by %obj-ref index, because a user slot-definition class may inherit the
;; seeded base through MULTIPLE inheritance with its own mixin first (mito's
;; table-column-class is (column-slot-definitions
;; c2mop:standard-direct-slot-definition)), which puts the mixin's slots ahead
;; of the base's in the layout -- no fixed index is valid across subclasses,
;; only the slot NAMES (standard-class: name, direct-superclasses,
;; direct-slots, effective-slots, finalized-p; slot definitions: name,
;; initargs, initform, type, readers, initfunction -- see
;; ClosRegistry.ensureMopClassesSeeded) are a contract.
;;
;; The class arguments of %mop-make-instance are DESIGNATORS (a metaobject or a
;; plain name symbol): the default direct/effective-slot-definition-class
;; methods answer the NAME, so the defaults drag no find-class runtime into the
;; program; a user method (postmodern returns (find-class 'direct-column-slot))
;; may answer the metaobject.
;;
;; Definition entry point: the driver routes through
;; closer-mop:ensure-class-using-class, dispatching on the EXISTING driver-built
;; metaobject (nil for a first definition) -- so a user :around specialized on a
;; metaclass (mito's dao-table-class superclass injection) fires on
;; REdefinition, per AMOP. Initialization runs through the ordinary generic
;; chain: %mop-make-instance allocates the instance UNBOUND and calls the
;; initialization generic, whose system shared-initialize primaries below
;; perform the initarg fill (%mop-fill-slots) -- which is what makes a user
;; initialize-instance :around's MUNGED initargs (mito's :direct-superclasses /
;; :direct-slots rewrites) actually take effect.

;; The classes the DRIVER has defined, most recent first -- deliberately
;; separate from the find-class memo: the compile paths' class table (and the
;; interpreter's registry) answer a materialized plain view for a class whose
;; driver call has not run yet, so find-class cannot tell "already ensured"
;; from "statically known".
(defvar %mop-ensured-classes% nil)

(defun %mop-ensured-class (name)
  (let ((hit nil))
    (dolist (pair %mop-ensured-classes%)
      (if (eq (car pair) name) (if hit nil (setq hit (cdr pair))) nil))
    hit))

(defun %mop-record-ensured-class (name class)
  (if (%mop-ensured-class name)
      nil
      (setq %mop-ensured-classes%
            (cons (cons name class) %mop-ensured-classes%)))
  class)

;; The leftmost tail of a plist whose key matches, nil when absent -- getf that
;; can tell a supplied nil from an absent key.
(defun %mop-initarg-tail (plist key)
  (do ((cursor plist (cdr (cdr cursor))))
      ((null cursor) nil)
    (if (eq (car cursor) key) (return (cdr cursor)) nil)))

(defun %mop-resolve-class-list (designators)
  ;; A :direct-superclasses element may be a name or a metaobject (a user
  ;; :around pushes (find-class 'dao-class) instances into the initargs);
  ;; storage always holds metaobjects. DEDUPED by resolved identity: the
  ;; compile paths emit the defclass with the superclasses the driver-built
  ;; metaobject held (the injected-superclass reconciliation, .kb/clos.md),
  ;; and a user :around that tests membership with the METAOBJECT against the
  ;; NAME list re-injects its class -- the first definition on the
  ;; interpreter never sees the widened list, so without the dedupe the two
  ;; paths diverge (and CL rejects duplicate direct superclasses anyway).
  (let ((out nil))
    (dolist (c designators)
      (let ((resolved (if (symbolp c) (find-class c) c)))
        (if (member resolved out) nil (setq out (cons resolved out)))))
    (reverse out)))

(defmethod closer-mop:validate-superclass (class superclass)
  ;; Permissive by design (lite divergence): CL's cross-metaclass rejection is
  ;; exactly what user methods exist to relax, and the static subset has no
  ;; incompatible-layout case of its own to guard.
  t)

(defmethod closer-mop:direct-slot-definition-class (class &rest initargs)
  'standard-direct-slot-definition)

(defmethod closer-mop:effective-slot-definition-class (class &rest initargs)
  'standard-effective-slot-definition)

;; The system initarg fill, run INSIDE the initialization chain (the least
;; specific primary a user :around's call-next-method reaches): store each
;; supplied initarg, then -- on initialize (slot-names t), not reinitialize
;; (slot-names nil) -- each still-unbound slot's initform. A class metaobject
;; additionally resolves :direct-superclasses designators and converts the
;; canonicalized :direct-slots spec plists into direct-slot-definition
;; metaobjects through the protocol, AFTER the raw fill (the raw plists land in
;; the slots first and are then replaced), so a user :around's post-
;; call-next-method code already sees real metaobjects.
(defmethod shared-initialize ((class standard-class) slot-names &rest initargs)
  (%mop-fill-slots class initargs slot-names)
  (let ((supers-tail (%mop-initarg-tail initargs ':direct-superclasses)))
    (if supers-tail
        (setf (slot-value class 'direct-superclasses)
              (%mop-resolve-class-list (car supers-tail)))
        nil))
  (let ((slots-tail (%mop-initarg-tail initargs ':direct-slots)))
    (if slots-tail
        (let ((direct-slots nil))
          (dolist (spec (car slots-tail))
            (setq direct-slots
                  (cons (apply #'%mop-make-instance
                               (apply #'closer-mop:direct-slot-definition-class
                                      class spec) spec) direct-slots)))
          (setf (slot-value class 'direct-slots) (reverse direct-slots)))
        nil))
  class)

(defmethod shared-initialize
    ((slot standard-direct-slot-definition) slot-names &rest initargs)
  (%mop-fill-slots slot initargs slot-names)
  slot)

(defmethod shared-initialize
    ((slot standard-effective-slot-definition) slot-names &rest initargs)
  (%mop-fill-slots slot initargs slot-names)
  slot)

;; Forces the reinitialize-instance generic into existence (the redefinition
;; path below calls it whether or not user methods exist); same chain CL's
;; default runs -- shared-initialize with slot-names nil, so initforms do NOT
;; refill on redefinition.
(defmethod reinitialize-instance ((class standard-class) &rest initargs)
  (apply #'shared-initialize class nil initargs))

(defmethod closer-mop:compute-effective-slot-definition
    (class name direct-slot-definitions)
  ;; The effective-slot-definition-class call AND the instantiation both happen
  ;; here, INSIDE the dynamic extent of a user override's call-next-method --
  ;; the contract postmodern's *direct-column-slot* binding (and the
  ;; :initform *direct-column-slot* of its effective slot class) relies on.
  (let ((dsd (car direct-slot-definitions)))
    (%mop-make-instance
     (closer-mop:effective-slot-definition-class class :name name)
     :name name
     :initargs (slot-value dsd 'initargs)
     :initform (slot-value dsd 'initform)
     :type (slot-value dsd 'type)
     :readers (slot-value dsd 'readers)
     :initfunction (slot-value dsd 'initfunction))))

(defmethod closer-mop:finalize-inheritance (class)
  ;; Effective slots in static-layout order: the superclasses' effective slots
  ;; first -- each superclass in local precedence order, the first occurrence
  ;; of a name keeping its place, like the static layout merge -- (recomputed
  ;; through the protocol when an own direct slot shadows one, reused as-is
  ;; otherwise -- a lite divergence: the direct-definition list handed to
  ;; compute-effective-slot-definition is the shadowing definition alone),
  ;; then the own slots that introduce new names.
  (let* ((supers (slot-value class 'direct-superclasses))
         (direct-slots (slot-value class 'direct-slots))
         (inherited nil)
         (effective nil))
    (dolist (super supers)
      (dolist (eslot (slot-value super 'effective-slots))
        (if (%mop-find-slot-definition (slot-value eslot 'name) inherited)
            nil
            (setq inherited (cons eslot inherited)))))
    (setq inherited (reverse inherited))
    (dolist (eslot inherited)
      (let ((own
             (%mop-find-slot-definition (slot-value eslot 'name) direct-slots)))
        (setq effective
              (cons (if own
                        (closer-mop:compute-effective-slot-definition class
                         (slot-value eslot 'name) (cons own nil))
                        eslot) effective))))
    (dolist (dsd direct-slots)
      (if (%mop-find-slot-definition (slot-value dsd 'name) inherited)
          nil
          (setq effective
                (cons (closer-mop:compute-effective-slot-definition class
                       (slot-value dsd 'name) (cons dsd nil)) effective))))
    (setf (slot-value class 'effective-slots) (reverse effective))
    (setf (slot-value class 'finalized-p) t)
    class))

(defun %mop-find-slot-definition (name slot-definitions)
  (let ((hit nil))
    (dolist (s slot-definitions)
      (if (eq (slot-value s 'name) name) (setq hit s) nil))
    hit))

(defmethod closer-mop:ensure-class-using-class (class name &rest initargs)
  ;; The AMOP default: nil class = first definition (instantiate the metaclass
  ;; through the initialization chain, validate, register, finalize); a class =
  ;; REdefinition (reinitialize the SAME metaobject in place -- identity
  ;; survives, per AMOP -- then re-finalize). The :metaclass initarg rides
  ;; along untouched; no slot declares it, so the fill ignores it.
  (let ((metaclass (car (%mop-initarg-tail initargs ':metaclass))))
    (if class
        (progn
          (apply #'reinitialize-instance class ':name name initargs)
          ;; Re-prime the memo: a redefinition invalidated the registry's view.
          (%register-class-metaobject name class)
          (closer-mop:finalize-inheritance class)
          class)
        (let ((new-class
               (apply #'%mop-make-instance metaclass ':name name initargs)))
          ;; AMOP default: a class with no direct superclasses gets
          ;; (standard-object) -- the walk shape libraries rely on (mito's
          ;; map-all-superclasses flushes its accumulator when the superclass
          ;; chain reaches (find-class 'standard-object)).
          (if (slot-value new-class 'direct-superclasses)
              nil
              (setf (slot-value new-class 'direct-superclasses)
                    (cons (find-class 'standard-object) nil)))
          (dolist (super (slot-value new-class 'direct-superclasses))
            (if (closer-mop:validate-superclass new-class super)
                nil
                (error "The class ~A cannot be a superclass of the ~A class ~A"
                       (slot-value super 'name) metaclass name)))
          ;; Register BEFORE finalizing, like CL's ensure-class: find-class must
          ;; answer THIS metaobject inside finalize-inheritance's user :after
          ;; hooks (the captured build-dao-methods method definitions read the
          ;; class back through (find-class 'name), see MopEvalCapture).
          (%register-class-metaobject name new-class)
          (%mop-record-ensured-class name new-class)
          (closer-mop:finalize-inheritance new-class)
          new-class))))

(defun %ensure-class-with-metaclass
    (name metaclass supers slot-specs class-initargs)
  ;; The definition-time driver: route through ensure-class-using-class with
  ;; the existing DRIVER-BUILT metaobject (nil on first definition), passing
  ;; the superclass NAMES (resolution happens in the shared-initialize fill,
  ;; AFTER user :arounds may have munged the list) and the canonicalized
  ;; slot-spec plists.
  (apply #'closer-mop:ensure-class-using-class (%mop-ensured-class name) name
         ':metaclass metaclass ':direct-superclasses supers ':direct-slots
         slot-specs class-initargs))
