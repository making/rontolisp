;; The blocks half of the new objc base: Objective-C blocks made from Lisp functions and
;; blocks called from Lisp -- make-objc-block, free-objc-block, with-objc-block,
;; call-objc-block, define-objc-block-type (the macros are objc-macros.lisp). LispWorks'
;; OBJC has no block interface (its FLI does), so the names are this package's own.
;; Written once and run on every target over the primitive layer, like objc.lisp
;; (.kb/objc.md, "Blocks and C functions"); spliced only into a program that names a block,
;; so a program that only calls carries none of it.
;;
;; A block's body is a Lisp function the host calls with the raw arguments
;; (objc::%make-block): an object argument arrives retained, and this file converts every
;; argument by the declared type, runs the function and converts the answer, exactly as
;; objc-class.lisp does for a method.
;;
;; Portability constraints honored here (like objc.lisp): do loops always declare at
;; least one variable; parameters are never assigned with setq.

;; A block made by make-objc-block: the literal's address (nil once freed), the
;; encoding the host calls it by, and the signature its descriptor carries.
(defstruct (objc:objc-block
            (:constructor objc::%make-objc-block (pointer types signature))
            (:predicate objc::%objc-block-p) (:conc-name objc::%block-)
            (:copier nil))
  pointer
  types
  signature)

(defmethod print-object ((block objc:objc-block) stream)
  (format stream "#<OBJC:OBJC-BLOCK ~a ~a>" (objc::%block-signature block)
          (if (objc::%block-pointer block) "live" "freed")))

;; define-objc-block-type's names: name -> (result-type argument-types).
(defvar objc::*block-types* (make-hash-table :test 'equal))

;; A block designator as (values result-type argument-types): a name
;; define-objc-block-type defined, or (result-type (argument-type...)).
(defun objc::%block-designator (type who)
  (cond
   ((and (consp type) (consp (cdr type)) (null (cddr type))
         (listp (second type)))
    (values (first type) (second type)))
   ((and type (symbolp type))
    (let ((named (gethash type objc::*block-types*)))
      (unless named
        (error "~a: ~s names no block type; define it with objc:define-objc-block-type, or pass (result-type (argument-type...))"
               who type))
      (values (first named) (second named))))
   (t (error
       "~a: ~s is not a block type: a name, or (result-type (argument-type...))"
       who type))))

;; The encoding a block is called by -- the block itself a plain pointer, which every host
;; parser reads -- and the signature its descriptor carries, the block spelled @?.
(defun objc::%block-encodings (result-type argument-types)
  (let ((out (make-string-output-stream)))
    (write-string (objc::%type-encoding result-type) out)
    (write-string "@?" out)
    (dolist (type argument-types)
      (write-string (objc::%type-encoding type) out))
    (let ((signature (get-output-stream-string out)))
      (values (objc::%callable-types signature) signature))))

(defun objc::%define-objc-block-type (name result-type argument-types)
  (unless (and name (symbolp name))
    (error "objc:define-objc-block-type: the name must be a symbol, got ~s"
           name))
  ;; The types are checked here, where the definition was written.
  (objc::%block-encodings result-type argument-types)
  (setf (gethash name objc::*block-types*) (list result-type argument-types))
  name)

;; A block's body as the host calls it: the raw arguments converted by their declared
;; types, the function's value converted back. A Lisp error is printed and the block
;; answers zero: nothing unwinds into the native frame that called it.
(defun objc::%run-block (function specs type raws)
  (handler-case (let ((converted nil) (rest raws))
                  (dolist (spec specs)
                    (push (objc::%convert-argument spec (car rest)) converted)
                    (setq rest (cdr rest)))
                  (objc::%callback-answer "objc:make-objc-block" type nil
                   (apply function (nreverse converted))))
    (error (condition)
      (format *error-output* "objc: error in a callback: ~a~%" condition)
      (objc::%zero-answer type))))

(defun objc:make-objc-block (type function)
  (unless (functionp function)
    (error "objc:make-objc-block: ~s is not a function" function))
  (multiple-value-bind (result-type argument-types)
      (objc::%block-designator type "objc:make-objc-block")
    (multiple-value-bind (types signature)
        (objc::%block-encodings result-type argument-types)
      (objc::%ready)
      (let ((specs (mapcar #'list argument-types))
            (declared (objc::%declared-type result-type)))
        (objc::%make-objc-block (objc::%make-block types signature
                                                   (lambda (raws)
                                                     (objc::%run-block function
                                                                       specs
                                                                       declared
                                                                       raws)))
                                types signature)))))

(defun objc:free-objc-block (block)
  (unless (objc::%objc-block-p block)
    (error "objc:free-objc-block: ~s is not an objc:objc-block" block))
  (let ((pointer (objc::%block-pointer block)))
    (when pointer
      (setf (objc::%block-pointer block) nil)
      (objc::%free-block pointer)))
  nil)

(defun objc:objc-block-live-p (block)
  (unless (objc::%objc-block-p block)
    (error "objc:objc-block-live-p: ~s is not an objc:objc-block" block))
  (if (objc::%block-pointer block) t nil))

(defun objc:objc-block-pointer (block)
  (unless (objc::%objc-block-p block)
    (error "objc:objc-block-pointer: ~s is not an objc:objc-block" block))
  (objc::%block-pointer block))

;; The address of any block: one made here (signals once freed), a block object an
;; Objective-C method answered, or a raw address.
(defun objc::%any-block-address (block who)
  (cond ((objc::%objc-block-p block)
         (or (objc::%block-pointer block)
             (error "~a: ~s has been freed" who block)))
        ((objc::%pointerp block) (objc::%pointer-address block))
        ((integerp block) block)
        (t (error "~a: ~s is not a block" who block))))

;; Calls a block, whoever made it, on the calling thread: its invoke function with the
;; block first, the arguments converted by the type given (a block's own signature is
;; not something a caller can rely on reading).
(defun objc:call-objc-block (type block &rest args)
  (multiple-value-bind (result-type argument-types)
      (objc::%block-designator type "objc:call-objc-block")
    (let ((address (objc::%any-block-address block "objc:call-objc-block")))
      (when (= address 0)
        (error "objc:call-objc-block: cannot call a null block"))
      (unless (= (length args) (length argument-types))
        (error "objc:call-objc-block: the block takes ~a argument(s), got ~a"
               (length argument-types) (length args)))
      (objc::%ready)
      (let* ((types (objc::%block-encodings result-type argument-types))
             (parsed (objc::%parse-encoding types))
             (return-type (first parsed))
             (temps (list nil))
             (raw
              (unwind-protect (let ((raws (list address))
                                    (params (cddr parsed))
                                    (i 0))
                                (dolist (value args)
                                  (push (objc::%raw-arg (car params) value
                                                        "objc:call-objc-block" i
                                                        temps) raws)
                                  (setq params (cdr params))
                                  (setq i (+ i 1)))
                                (or (objc::%call-function
                                     (objc::%peek (+ address 16) "^v") types -1
                                     (nreverse raws)
                                     (if (eq return-type :object) 1 0))
                                    (objc::%checked "objc:call-objc-block"
                                                    :function)))
                (when (car temps)
                  (dolist (temp (car temps)) (objc:release temp))))))
        (if (eq (objc::%declared-type result-type) :boolean)
            (/= raw 0)
            (objc::%result return-type raw))))))

(setq objc::*block-pointer-hook*
      (lambda (value)
        (and (objc::%objc-block-p value)
             (objc::%any-block-address value "objc:invoke"))))
