;; The slot-access protocol (AMOP 5.5): slot-value, (setf slot-value),
;; slot-boundp, slot-makunbound and the accessors of an instance whose class is
;; an instance of a USER metaclass go through slot-value-using-class,
;; (setf slot-value-using-class), slot-boundp-using-class and
;; slot-makunbound-using-class with the class metaobject and the effective
;; slot definition -- contextl's special slots are methods on them. The
;; expansions of those forms test %slot-protocol-instance-p and take the
;; ordinary static access otherwise, so an instance of a plain class pays one
;; test.
;;
;; Injected only into programs defining a method on one of the four generics
;; (LispMacroExpander gate, beside mop-protocol.lisp); the interpreter
;; evaluates the forms when it first meets such a method. Canonical shape, like
;; mop-protocol.lisp. The system methods reach the storage through the runtime
;; slot-name dispatch (%slot-value-runtime, %slot-value-set-runtime), never
;; through slot-value, whose expansion would route back here. The methods come
;; first so the (setf slot-value-using-class) place is known to the defuns.

(defun %slot-protocol-name (slotd) (%slot-value-runtime slotd 'name))

;; The system methods: the instance's own storage (a class-allocated slot's
;; shared cell included), an unbound slot answering slot-unbound.
(defmethod closer-mop:slot-value-using-class
    ((class standard-class) object slotd)
  (let* ((name (%slot-protocol-name slotd))
         (value (%slot-value-runtime object name)))
    (if (%obj-is value '|%class-%UNBOUND%|)
        (slot-unbound class object name)
        value)))

(defmethod (setf closer-mop:slot-value-using-class)
    (new-value (class standard-class) object slotd)
  (%slot-value-set-runtime object (%slot-protocol-name slotd) new-value))

(defmethod closer-mop:slot-boundp-using-class
    ((class standard-class) object slotd)
  (if (%obj-is (%slot-value-runtime object (%slot-protocol-name slotd))
               '|%class-%UNBOUND%|)
      nil
      t))

(defmethod closer-mop:slot-makunbound-using-class
    ((class standard-class) object slotd)
  (%slot-value-set-runtime object (%slot-protocol-name slotd)
                           (%obj-new '|%class-%UNBOUND%|))
  object)

(defun %slot-protocol-instance-p (object)
  ;; An instance whose class metaobject is not a direct instance of
  ;; standard-class -- a class defined with a :metaclass.
  (if (%obj-p object)
      (let ((class (class-of object)))
        (if (%obj-p class)
            (not (eq (class-of class) (find-class 'standard-class)))
            nil))
      nil))

(defun %slot-protocol-slotd (class name)
  ;; The effective slot definition named NAME, by symbol name (slot lookup is
  ;; by base name, like every static access).
  (let ((hit nil) (wanted (symbol-name name)))
    (dolist (slotd (%slot-value-runtime class 'effective-slots))
      (if (and (null hit)
               (string= (symbol-name (%slot-protocol-name slotd)) wanted))
          (setq hit slotd)
          nil))
    hit))

(defun %slot-protocol-value (object name)
  (let ((class (class-of object)))
    (closer-mop:slot-value-using-class class object
                                       (%slot-protocol-slotd class name))))

(defun %slot-protocol-set (object name value)
  (let ((class (class-of object)))
    (setf (closer-mop:slot-value-using-class class object
                                             (%slot-protocol-slotd class name))
          value)
    value))

(defun %slot-protocol-boundp (object name)
  (let ((class (class-of object)))
    (if (closer-mop:slot-boundp-using-class class object
                                            (%slot-protocol-slotd class name))
        t
        nil)))

(defun %slot-protocol-initarg-tail (initargs keys)
  ;; The tail of INITARGS at its leftmost key KEYS holds, nil when none does.
  (do ((cursor initargs (cdr (cdr cursor))))
      ((null cursor) nil)
    (if (member (car cursor) keys) (return (cdr cursor)) nil)))

(defun %slot-protocol-fill (object initargs slot-names)
  ;; CLHS 7.1.4 through the protocol: a slot an initarg names takes the
  ;; leftmost such value; otherwise a slot SLOT-NAMES selects (t: every one)
  ;; that is still unbound takes its initfunction's value.
  (let ((class (class-of object)))
    (dolist (slotd (%slot-value-runtime class 'effective-slots))
      (let ((tail
             (%slot-protocol-initarg-tail initargs
              (%slot-value-runtime slotd 'initargs)))
            (initfunction (%slot-value-runtime slotd 'initfunction)))
        (cond (tail (setf (closer-mop:slot-value-using-class class object slotd)
                          (car tail)))
              ((and initfunction
                (or (eq slot-names t)
                    (member (%slot-protocol-name slotd) slot-names))
                (not (closer-mop:slot-boundp-using-class class object slotd)))
               (setf (closer-mop:slot-value-using-class class object slotd)
                     (funcall initfunction)))
              (t nil))))
    object))

(defun %slot-protocol-makunbound (object name)
  (let ((class (class-of object)))
    (closer-mop:slot-makunbound-using-class class object
                                            (%slot-protocol-slotd class name))
    object))
