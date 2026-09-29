;; The definition half of the new objc base: LispWorks 8.1's standard-objc-object, the
;; classes and methods define-objc-class / define-objc-method / define-objc-class-method
;; make (their macros are objc-macros.lisp), current-super, the instance variables,
;; objc-object-copied / objc-object-destroyed, define-objc-struct / -typedef / -protocol.
;; Written once and run on every target over the primitive layer, like objc.lisp
;; (.kb/objc.md, "Class definition"); spliced only into a program that
;; defines something, so a program that only calls carries none of it.
;;
;; A method's body is a Lisp function the host calls with the receiver's address and the
;; raw arguments (objc::%add-method): an object argument arrives retained, and this file
;; converts every argument by its declared type, runs the body and converts the answer.
;;
;; Portability constraints honored here (like objc.lisp): do loops always declare at
;; least one variable; parameters are never assigned with setq.

;;; --- the Lisp side of a class -------------------------------------------------------

;; An instance of a class defined in Lisp. The one slot is the object's pointer; its
;; name is unlikely to meet a user slot, since slots are matched by name.
(defclass objc:standard-objc-object () ((objc::%objc-pointer% :initform nil)))

;; What define-objc-class recorded about a Lisp class.
(defstruct (objc::lisp-class (:constructor objc::%make-lisp-class
                                           (name supers objc-name objc-super
                                                 ivars protocols))
                             (:conc-name objc::%lc-) (:copier nil)
                             (:predicate nil))
  name
  supers
  objc-name
  objc-super
  ivars
  protocols
  (address 0)
  (realized nil))

;; A method define-objc-method or define-objc-class-method recorded.
(defstruct (objc::method-def (:constructor objc::%make-method-def
                                           (class-name selector result-type
                                                       result-style result-var-p
                                                       arg-specs class-method-p
                                                       function))
                             (:conc-name objc::%md-) (:copier nil)
                             (:predicate nil))
  class-name
  selector
  result-type
  result-style
  result-var-p
  arg-specs
  class-method-p
  function)

;; Lisp class name -> its record.
(defvar objc::*lisp-classes* (make-hash-table :test 'equal))

;; The records in definition order, newest first.
(defvar objc::*lisp-class-order* nil)

;; Objective-C class name -> the Lisp class name implementing it.
(defvar objc::*lisp-class-by-objc-name* (make-hash-table :test 'equal))

;; The addresses of the Objective-C classes this program realized.
(defvar objc::*own-classes* (make-hash-table))

;; Object address -> the standard-objc-object made for it. Strong, as LispWorks'
;; own: the object is removed when its reference count reaches zero (-dealloc).
(defvar objc::*lisp-objects* (make-hash-table))

;; Every method definition, newest first.
(defvar objc::*method-defs* nil)

;; The instance make-instance is allocating, handed to +allocWithZone:. A global value,
;; not a binding: the method runs on thread 0, where the program's bindings are not.
(defvar objc::*adopting* nil)

;; define-objc-protocol's declarations, by name.
(defvar objc::*protocols* (make-hash-table :test 'equal))

(defun objc::%record (name who)
  (or (gethash name objc::*lisp-classes*)
      (error "~a: ~a is not a class defined with objc:define-objc-class" who
             name)))

;; The Objective-C class name a Lisp class inherits through its Lisp superclasses, the
;; first one met depth first, or nil.
(defun objc::%inherited-objc-name (record)
  (let ((found nil))
    (dolist (super (objc::%lc-supers record))
      (let ((parent (gethash super objc::*lisp-classes*)))
        (when (and parent (null found))
          (setq found
                (or (objc::%lc-objc-name parent)
                    (objc::%inherited-objc-name parent))))))
    found))

;; Whether a class names no Objective-C class and inherits none: its methods go to every
;; subclass that does.
(defun objc::%mixin-p (record)
  (and (null (objc::%lc-objc-name record))
       (null (objc::%inherited-objc-name record))))

(defun objc::%ancestor-p (record name)
  (let ((found nil))
    (dolist (super (objc::%lc-supers record))
      (when (and (null found)
                 (or (equal super name)
                     (let ((parent (gethash super objc::*lisp-classes*)))
                       (and parent (objc::%ancestor-p parent name)))))
        (setq found t)))
    found))

(defun objc::%define-objc-class
    (name supers objc-name objc-super ivars protocols)
  (let ((record
         (objc::%make-lisp-class name supers objc-name objc-super ivars
                                 protocols)))
    (setf (gethash name objc::*lisp-classes*) record)
    (setq objc::*lisp-class-order*
          (cons name (remove name objc::*lisp-class-order* :test #'equal)))
    (when objc::*initialized* (objc::%realize record))
    name))

;; Every class defined before the runtime was up (objc.lisp's %ready runs this).
(defun objc::%realize-pending ()
  (dolist (name (reverse objc::*lisp-class-order*))
    (objc::%realize (gethash name objc::*lisp-classes*))))

(setq objc::*realize-hook* #'objc::%realize-pending)

(defun objc::%realize (record)
  (unless (objc::%lc-realized record)
    (let ((inherited (objc::%inherited-objc-name record))
          (objc-name (objc::%lc-objc-name record)))
      (cond ((null objc-name)
             ;; A mixin creates nothing; a class inheriting one only reuses it.
             (when inherited
               (setf (objc::%lc-address record)
                (objc::%pointer-address (objc:coerce-to-objc-class inherited))))
             (setf (objc::%lc-realized record) t))
            (t (let ((declared (objc::%lc-objc-super record)))
                 (when (and declared inherited (string/= declared inherited))
                   (error "objc:define-objc-class: ~s inherits the Objective-C class ~a but names ~a as its superclass"
                          (objc::%lc-name record) inherited declared))
                 (let* ((super
                         (objc::%pointer-address
                          (objc:coerce-to-objc-class
                           (or inherited declared "NSObject"))))
                        (cls (objc::%class-for record super)))
                   (setf (objc::%lc-address record) cls)
                   (setf (objc::%lc-realized record) t)
                   (setf (gethash objc-name objc::*lisp-class-by-objc-name*)
                         (objc::%lc-name record))
                   (setf (gethash cls objc::*own-classes*) t)
                   ;; A root of a Lisp-defined hierarchy gets the three methods every instance
                   ;; needs; its subclasses inherit them.
                   (unless (gethash super objc::*own-classes*)
                     (objc::%install-root-methods cls))
                   (dolist (def (reverse objc::*method-defs*))
                     (when (objc::%installs-on-p def record)
                       (objc::%install def cls))))))))))

;; A class pair, allocated with its instance variables and protocols and registered --
;; or the class of that name this process defined before (a re-evaluated definition),
;; whose methods are then replaced.
(defun objc::%class-for (record super)
  (let* ((objc-name (objc::%lc-objc-name record))
         (existing (objc::%get-class objc-name)))
    (if (/= existing 0)
        (let ((cls (objc::%allocate-class super objc-name)))
          (when (= cls 0)
            (error "objc:define-objc-class: the Objective-C class ~a already exists and was not defined by this process"
                   objc-name))
          cls)
        (let ((cls (objc::%allocate-class super objc-name)))
          (when (= cls 0)
            (error
             "objc:define-objc-class: the runtime refused the class name ~a"
             objc-name))
          (dolist (ivar (objc::%lc-ivars record))
            (let ((types (objc::%type-encoding (second ivar)))
                  (layout (objc::%fli-layout (second ivar))))
              (unless (objc::%add-ivar cls (first ivar) (car layout)
                                       (cdr layout) types)
                (error "objc:define-objc-class: the runtime refused the instance variable ~s of ~a"
                       (first ivar) objc-name))))
          (dolist (protocol (objc::%lc-protocols record))
            (unless (objc::%add-protocol cls protocol)
              (warn "objc:define-objc-class: no Objective-C protocol named ~a; ~a does not adopt it"
                    protocol objc-name)))
          (objc::%register-class cls)
          cls))))

;;; --- methods ------------------------------------------------------------------------

(defun objc::%colons (selector) (count #\: selector))

(defun objc::%define-objc-method (class-name selector result-type result-style
                                             result-var-p arg-specs
                                             class-method-p function)
  (let ((who
         (if class-method-p
             "objc:define-objc-class-method"
             "objc:define-objc-method")))
    (unless (stringp selector)
      (error "~a: the method name must be a string, got ~s" who selector))
    (objc::%record class-name who)
    (unless (= (objc::%colons selector) (length arg-specs))
      (error "~a: ~s takes ~a argument(s) but ~a are declared" who selector
             (objc::%colons selector) (length arg-specs)))
    (let ((def
           (objc::%make-method-def class-name selector result-type result-style
                                   result-var-p arg-specs class-method-p
                                   function)))
      ;; The encoding is checked here, where the definition was written.
      (objc::%def-types def)
      (setq objc::*method-defs*
            (cons def
                  (remove-if (lambda (old)
                               (and
                                (equal (objc::%md-class-name old) class-name)
                                (string= (objc::%md-selector old) selector)
                                (eq (objc::%md-class-method-p old)
                                    class-method-p))) objc::*method-defs*)))
      (when objc::*initialized*
        (dolist (name (reverse objc::*lisp-class-order*))
          (let ((record (gethash name objc::*lisp-classes*)))
            (when (and (objc::%lc-realized record)
                       (objc::%installs-on-p def record))
              (objc::%install def (objc::%lc-address record))))))
      selector)))

;; Whether a definition belongs on a realized class: its own, or a mixin ancestor's.
(defun objc::%installs-on-p (def record)
  (let ((owner (objc::%md-class-name def)))
    (if (equal owner (objc::%lc-name record))
        (/= (objc::%lc-address record) 0)
        (and (objc::%lc-objc-name record) (objc::%ancestor-p record owner)
             (objc::%mixin-p (gethash owner objc::*lisp-classes*))))))

;; The method's encoding, from its declared FLI types.
(defun objc::%def-types (def)
  (let ((out (make-string-output-stream)))
    (write-string (objc::%type-encoding (objc::%md-result-type def)) out)
    (write-string "@:" out)
    (dolist (spec (objc::%md-arg-specs def))
      (write-string (objc::%type-encoding (first spec)) out))
    (get-output-stream-string out)))

;; An object answer is retained for the caller, and autoreleased unless the method is
;; of a family whose caller owns the answer (ARC's rule, as for a send).
(defun objc::%result-flags (selector) (if (objc::%owned-result-p selector) 1 3))

(defun objc::%install (def address)
  (let* ((cls
          (if (objc::%md-class-method-p def)
              (objc::%object-class address)
              address))
         (super (objc::%superclass cls)))
    (objc::%add-method cls (objc::%sel-address (objc::%md-selector def))
     (objc::%def-types def)
     (lambda (self args) (objc::%run-method def super self args))
     (objc::%result-flags (objc::%md-selector def)))))

(defun objc::%self-object (self class-method-p)
  (if class-method-p
      (let ((name
             (gethash (objc::%class-name self)
                      objc::*lisp-class-by-objc-name*)))
        (if name (find-class name) (objc::%intern-class self)))
      (or (gethash self objc::*lisp-objects*) (objc::%borrow self))))

;; A method with a result variable (a non-keyword result style) fills a foreign object of
;; its result type -- fli:foreign-slot-value, cocoa:set-ns-rect* -- which is answered and
;; freed when the body returns.
(defun objc::%run-method (def super self args)
  (let ((type (objc::%declared-type (objc::%md-result-type def))) (struct nil))
    (handler-case (unwind-protect (progn
                                    (when (objc::%md-result-var-p def)
                                      (setq struct
                                            (fli:allocate-foreign-object
                                             :type
                                             (objc::%md-result-type def))))
                                    (objc::%method-answer def type
                                                          (objc::%call-method
                                                           def super self args
                                                           struct type)))
                    (when struct (fli:free-foreign-object struct)))
      (error (condition)
        (format *error-output* "objc: error in a callback: ~a~%" condition)
        (objc::%zero-answer type)))))

;; The body run on the converted arguments: its value, or what the result variable holds.
(defun objc::%call-method (def super self args struct type)
  (let ((converted nil) (raws args))
    (dolist (spec (objc::%md-arg-specs def))
      (push (objc::%convert-argument spec (car raws)) converted)
      (setq raws (cdr raws)))
    (let ((answer
           (funcall (objc::%md-function def) (objc::%make-super-ref self super)
                    (objc::%self-object self (objc::%md-class-method-p def))
                    (if (objc::%md-class-method-p def)
                        (objc::%intern-class self)
                        (objc::%borrow self)) struct (nreverse converted))))
      (cond ((null struct) answer)
            ((and (consp type) (eq (car type) :struct)) struct)
            (t (fli:dereference struct))))))

;; A method's value as the host takes it back.
(defun objc::%method-answer (def type value)
  (objc::%callback-answer (objc::%md-selector def) type
                          (objc::%md-result-style def) value))

;;; --- the three methods of a root class ----------------------------------------------

(defun objc::%install-root-methods (cls)
  (let* ((meta (objc::%object-class cls))
         (meta-super (objc::%superclass meta))
         (super (objc::%superclass cls)))
    (objc::%add-method meta (objc::%sel-address "allocWithZone:") "@@:^v"
                       (lambda (self args)
                         (objc::%guarded
                          (lambda ()
                            (objc::%alloc-with-zone self meta-super
                                                    (car args))))) 0)
    (objc::%add-method cls (objc::%sel-address "copyWithZone:") "@@:^v"
                       (lambda (self args)
                         (objc::%guarded
                          (lambda () (objc::%copy-with-zone self (car args)))))
                       0)
    (objc::%add-method cls (objc::%sel-address "dealloc") "v@:"
                       (lambda (self args)
                         (declare (ignore args))
                         (objc::%guarded
                          (lambda () (objc::%dealloc self super)))) 0)))

(defun objc::%guarded (thunk)
  (handler-case (funcall thunk)
    (error (condition)
      (format *error-output* "objc: error in a callback: ~a~%" condition)
      0)))

;; +allocWithZone: -- every allocation of such a class, +alloc included, comes here, so
;; this is where the Lisp object for an object comes into being: the one make-instance
;; is building, or a fresh one when Objective-C allocated. The answer is the +1 the
;; alloc family hands over.
(defun objc::%alloc-with-zone (cls meta-super zone)
  (let ((raw
         (or (objc::%send-super cls meta-super
                                (objc::%sel-address "allocWithZone:") "@@:^v" -1
                                (list zone) 0)
             (objc::%checked "allocWithZone:" :class))))
    (unless (= raw 0)
      (let ((name
             (gethash (objc::%class-name cls) objc::*lisp-class-by-objc-name*))
            (adopting objc::*adopting*))
        (when name
          (if (and adopting (equal (class-name (class-of adopting)) name))
              (progn
                (setq objc::*adopting* nil)
                (objc::%register adopting raw))
              (make-instance (find-class name) :%objc-pointer raw)))))
    raw))

;; -copyWithZone: -- the copy's own Lisp object (made by +allocWithZone:) gets the
;; original's slots through objc-object-copied. The answer is the copy family's +1.
(defun objc::%copy-with-zone (self zone)
  (let* ((cls (objc::%object-class self))
         (copy
          (or (objc::%send cls (objc::%sel-address "allocWithZone:") "@@:^v" -1
                           (list zone) 0)
              (objc::%checked "allocWithZone:" :class))))
    (if (= copy 0)
        0
        (let ((initialized
               (or (objc::%send copy (objc::%sel-address "init") "@@:" -1 nil 0)
                   (objc::%checked "init" :instance))))
          (let ((old (gethash self objc::*lisp-objects*))
                (new (gethash initialized objc::*lisp-objects*)))
            (when (and old new) (objc:objc-object-copied old new)))
          initialized))))

;; -dealloc -- the reference count reached zero: objc-object-destroyed, then the Lisp
;; object leaves the table, then the superclass's -dealloc.
(defun objc::%dealloc (self super)
  (let ((object (gethash self objc::*lisp-objects*)))
    (when object
      (handler-case (objc:objc-object-destroyed object)
        (error (condition)
          (format *error-output* "objc: error in objc-object-destroyed: ~a~%"
                  condition))))
    (remhash self objc::*lisp-objects*)
    (or (objc::%send-super self super (objc::%sel-address "dealloc") "v@:" -1
                           nil 0) (objc::%checked "dealloc" :instance))
    nil))

(defun objc::%register (instance address)
  (setf (slot-value instance 'objc::%objc-pointer%) (objc::%borrow address))
  (setf (gethash address objc::*lisp-objects*) instance)
  instance)

;;; --- standard-objc-object ---------------------------------------------------------

;; make-instance: +alloc (which adopts this instance), the slots, then -init or the
;; :init-function, whose answer is the object -- a class cluster may answer another.
(defmethod initialize-instance :around ((self objc:standard-objc-object) &rest
                                        initargs &key %objc-pointer
                                        init-function &allow-other-keys)
  (if %objc-pointer
      (progn
        (call-next-method)
        (objc::%register self %objc-pointer))
      (let* ((record
              (objc::%record (class-name (class-of self)) "make-instance"))
             (cls (objc::%class-address record)))
        (setq objc::*adopting* self)
        (let ((raw
               (unwind-protect (objc:invoke (objc::%intern-class cls) "alloc")
                 (setq objc::*adopting* nil))))
          (call-next-method)
          (let ((final
                 (if init-function
                     (apply init-function raw initargs)
                     (objc:invoke raw "init"))))
            (when (and raw
                       (not
                        (eql (objc::%pointer-address raw)
                             (and final (objc::%pointer-address final)))))
              (remhash (objc::%pointer-address raw) objc::*lisp-objects*))
            (setf (slot-value self 'objc::%objc-pointer%) final)
            (when final
              (setf
               (gethash (objc::%pointer-address final) objc::*lisp-objects*)
               self))
            self)))))

;; The Objective-C class of a Lisp class, realized on demand.
(defun objc::%class-address (record)
  (objc::%ready)
  (objc::%realize record)
  (when (= (objc::%lc-address record) 0)
    (error "~a does not implement an Objective-C class"
           (objc::%lc-name record)))
  (objc::%lc-address record))

(defgeneric objc:objc-object-destroyed (object)
  (:method ((object objc:standard-objc-object)) nil))

;; The built-in primary method copies the slots, the pointer aside.
(defgeneric objc:objc-object-copied (old-object new-object)
  (:method ((old-object objc:standard-objc-object)
            (new-object objc:standard-objc-object))
           (dolist (def (%class-slot-defs (%class-designator old-object)))
             (let ((name (car def)))
               (when (and (string/= (symbol-name name) "%OBJC-POINTER%")
                          (slot-boundp old-object name))
                 (setf (slot-value new-object name)
                       (slot-value old-object name))))) new-object))

;; objc.lisp's hooks: what stands for a pointer, and what a pointer maps back to.
(defun objc::%lisp-object-pointer (value)
  (cond ((typep value 'objc:standard-objc-object)
         (slot-value value 'objc::%objc-pointer%))
        ((and (typep value 'standard-class)
              (gethash (class-name value) objc::*lisp-classes*))
         (objc::%intern-class
          (objc::%class-address
           (gethash (class-name value) objc::*lisp-classes*))))
        (t nil)))

(defun objc::%pointer-lisp-object (pointer)
  (if (objc::%classp pointer)
      (let ((name
             (gethash (objc:objc-class-name pointer)
                      objc::*lisp-class-by-objc-name*)))
        (if name (find-class name) nil))
      (gethash (objc::%pointer-address pointer) objc::*lisp-objects*)))

(defun objc::%registered-pointer (address)
  (let ((object (gethash address objc::*lisp-objects*)))
    (if object (slot-value object 'objc::%objc-pointer%) nil)))

(setq objc::*object-pointer-hook* #'objc::%lisp-object-pointer)

(setq objc::*registered-pointer-hook* #'objc::%registered-pointer)

(setq objc::*pointer-object-hook* #'objc::%pointer-lisp-object)

;;; --- instance variables -----------------------------------------------------------

(defun objc::%ivar (object var-name who)
  (let* ((address (objc::%pointer-address (objc:objc-object-pointer object)))
         (cls (objc::%object-class address))
         (offset (objc::%ivar-offset cls var-name)))
    (when (< offset 0)
      (error "~a: ~a has no instance variable ~s" who (objc::%class-name cls)
             var-name))
    (values (+ address offset) (objc::%ivar-types cls var-name))))

(defun objc:objc-object-var-value (object var-name &key result-pointer)
  (multiple-value-bind (at types)
      (objc::%ivar object var-name "objc:objc-object-var-value")
    (let* ((type (car (objc::%parse-type types 0)))
           (value (objc::%result type (objc::%peek at types))))
      (if result-pointer (objc::%into result-pointer type value) value))))

(defun (setf objc:objc-object-var-value)
    (value object var-name &key result-pointer)
  (declare (ignore result-pointer))
  (multiple-value-bind (at types)
      (objc::%ivar object var-name "(setf objc:objc-object-var-value)")
    (let ((type (car (objc::%parse-type types 0))))
      (objc::%poke at types
       (if (eq type :object)
           (if value
               (objc::%pointer-address
                (or (objc::%as-pointer value)
                    (error "(setf objc:objc-object-var-value): ~s is not an Objective-C object"
                           value)))
               0)
           (objc::%raw-arg type value var-name 0 (list nil))))
      value)))

;;; --- structures, typedefs, protocols ------------------------------------------------

(defun objc::%define-objc-struct (name options slots)
  (let ((foreign-name (second (assoc :foreign-name options)))
        (typedef-name (second (assoc :typedef-name options)))
        (out (make-string-output-stream)))
    (unless (stringp foreign-name)
      (error
       "objc:define-objc-struct: ~s needs a (:foreign-name \"name\") option"
       name))
    (write-string "{" out)
    (write-string foreign-name out)
    (write-string "=" out)
    (dolist (slot slots)
      (write-string (objc::%type-encoding (second slot)) out))
    (write-string "}" out)
    (let ((encoding (get-output-stream-string out)))
      (setf (gethash name objc::*type-encodings*) encoding)
      (setf (gethash name objc::*struct-slots*) slots)
      (setf (gethash foreign-name objc::*struct-names*) name)
      (when typedef-name
        (setf (gethash typedef-name objc::*type-encodings*) encoding)
        (setf (gethash typedef-name objc::*struct-slots*) slots))
      (objc::%forget-elements)
      name)))

(defun objc::%define-objc-typedef (name options type)
  (let ((c-type (assoc :c-type options)))
    (setf (gethash name objc::*type-encodings*)
          (objc::%type-encoding (if c-type (second c-type) type)))
    (objc::%forget-elements)
    name))

;; A declaration of a protocol the runtime already has: the manual's rule since macOS
;; 10.5, when a protocol stopped being something Lisp could create.
(defun objc::%define-objc-protocol
    (name incorporated instance-methods class-methods)
  (unless (stringp name)
    (error "objc:define-objc-protocol: the name must be a string, got ~s" name))
  (setf (gethash name objc::*protocols*)
        (list incorporated instance-methods class-methods))
  name)
