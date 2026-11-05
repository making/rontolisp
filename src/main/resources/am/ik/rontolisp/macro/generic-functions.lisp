;; The generic-function protocol (AMOP 5.3-5.5): generic function and method
;; METAOBJECTS for a program that asks for them -- ensure-generic-function,
;; add-method / remove-method / find-method, closer-mop's ensure-method and the
;; readers. A generic function metaobject is a FUNCALLABLE instance of
;; standard-generic-function (or of the class its defgeneric names), a method one
;; of standard-method (or of the generic's method class); slots are read by name.
;;
;; The generics a program DEFINES keep their compiled dispatchers: a method added
;; at RUN time joins its generic's run-time method table, which every dispatcher
;; consults first (LispMacroExpander.withDynamicDispatchArm): with run-time
;; methods present, the dispatcher computes every method's entry -- applicability
;; and precedence keys -- and runs the standard method combination over them
;; (%dynamic-dispatch, dynamic-classes.lisp). A generic made only at run time
;; dispatches over its table alone. A defmethod on a generic whose defgeneric
;; names a generic-function class reaches add-method with a metaobject of the
;; method it defined (%gf-static-method-added), so a user add-method method sees
;; it (contextl clears its layer caches from one).
;;
;; Injected only into programs naming the protocol (LispMacroExpander gate); the
;; interpreter evaluates the forms at the first use. Canonical shape, like
;; mop-protocol.lisp; no prelude defun is used (the prelude splice ran before the
;; prepend).

;; Generic function key -> metaobject. The key is the symbol naming the
;; generic's function: a (setf x) name's %setf- writer symbol.
(defvar %gf-metaobjects% nil)

;; Generic function key -> the methods added at run time, in addition order.
(defvar %gf-runtime-method-table% nil)

;; Generic function key -> (class method-class lambda-list name) of a defgeneric
;; that named its metaobject classes.
(defvar %gf-declarations% nil)

;; eql-specializer metaobjects by object (closer-mop:intern-eql-specializer).
(defvar %gf-eql-specializers% nil)

(defun %gf-required-args (lambda-list)
  ;; The parameters ahead of the first lambda-list keyword.
  (let ((out nil) (done nil))
    (dolist (parameter lambda-list)
      (if (or done (member parameter lambda-list-keywords))
          (setq done t)
          (setq out (cons parameter out))))
    (reverse out)))

(defun %gf-key (name)
  (if (consp name) (%setf-function-symbol (car (cdr name))) name))

(defun %gf-runtime-methods (key)
  ;; What every dispatcher asks first: nil (no run-time method) keeps the
  ;; compiled dispatch.
  (if %gf-runtime-method-table%
      (cdr (assoc key %gf-runtime-method-table% :test #'equal))
      nil))

(defun %gf-metaobject (name)
  (cdr (assoc (%gf-key name) %gf-metaobjects% :test #'equal)))

(defun %gf-installed-p (name)
  ;; Whether the name's function is its metaobject: a definition of the generic
  ;; leaves it there, and the metaobject's %STATIC is the compiled dispatcher.
  (let ((gf (%gf-metaobject name)))
    (if (and gf (fboundp name)) (eq (fdefinition name) gf) nil)))

(defun %gf-declare (name class method-class lambda-list)
  ;; A defgeneric naming its generic-function class (or method class): the
  ;; metaobject made for it is an instance of that class.
  (setq %gf-declarations%
        (cons (cons (%gf-key name) (list class method-class lambda-list name))
              %gf-declarations%))
  name)

(defun %gf-class-designator (designator)
  (if (symbolp designator) (find-class designator) designator))

(defun %gf-invoke (gf args)
  ;; The function of a generic function metaobject: the compiled dispatcher of a
  ;; generic the program defines (which consults the run-time methods itself),
  ;; else the run-time methods alone.
  (let ((static (slot-value gf '%static)))
    (if static
        (apply static args)
        (let ((entries
               (%dynamic-applicable
                (%gf-runtime-entries
                 (%gf-runtime-methods (%gf-key (slot-value gf 'name))) args
                 (%gf-precedence-indexes gf)))))
          (if entries
              (%dynamic-dispatch entries args)
              (error "There is no applicable method for the generic function ~S when called with arguments ~S."
                     (slot-value gf 'name) args))))))

(defun %gf-make (name class method-class lambda-list argument-precedence-order)
  (let* ((key (%gf-key name))
         (static (if (fboundp name) (fdefinition name) nil))
         (initargs
          (append (list ':name name ':lambda-list lambda-list)
                  (if method-class
                      (list ':method-class (%gf-class-designator method-class))
                      nil)
                  (if argument-precedence-order
                      (list ':argument-precedence-order
                            argument-precedence-order)
                      nil)))
         (gf (apply #'make-instance (%gf-class-designator class) initargs)))
    (if (slot-value gf 'method-class)
        nil
        (setf (slot-value gf 'method-class) (find-class 'standard-method)))
    (setf (slot-value gf '%static) static)
    ;; The funcallable instance's function (LispLayout.FUNCALLABLE_CELL).
    (%obj-set gf -2 (lambda (&rest args) (%gf-invoke gf args)))
    (setq %gf-metaobjects% (cons (cons key gf) %gf-metaobjects%))
    (setf (fdefinition name) gf)
    gf))

(defun %gf-ensure (name)
  ;; The metaobject of a generic, made on first request: of the class its
  ;; defgeneric declared, else standard-generic-function.
  (let ((gf (%gf-metaobject name)))
    (if gf
        gf
        (let ((declared
               (cdr (assoc (%gf-key name) %gf-declarations% :test #'equal))))
          (if declared
              (%gf-make (car (cdr (cdr (cdr declared)))) (car declared)
                        (car (cdr declared)) (car (cdr (cdr declared))) nil)
              (%gf-make name 'standard-generic-function nil nil nil))))))

(defun ensure-generic-function (name &rest initargs &key
                                     (generic-function-class nil class-p)
                                     lambda-list method-class
                                     argument-precedence-order
                                     &allow-other-keys)
  (let ((gf (%gf-metaobject name)))
    (if gf
        gf
        (if class-p
            (%gf-make name generic-function-class method-class lambda-list
                      argument-precedence-order)
            (let ((declared
                   (cdr
                    (assoc (%gf-key name) %gf-declarations% :test #'equal))))
              (if declared
                  (%gf-ensure name)
                  (%gf-make name 'standard-generic-function method-class
                            lambda-list argument-precedence-order)))))))

;; The readers.
(defun closer-mop:generic-function-name (gf) (slot-value gf 'name))

(defun closer-mop:generic-function-methods (gf) (slot-value gf 'methods))

(defun closer-mop:generic-function-method-class (gf)
  (slot-value gf 'method-class))

(defun closer-mop:generic-function-argument-precedence-order (gf)
  (let ((order (slot-value gf 'argument-precedence-order)))
    (if order order (%gf-required-args (slot-value gf 'lambda-list)))))

(defun closer-mop:generic-function-method-combination (gf)
  (slot-value gf 'method-combination))

(defun closer-mop:generic-function-declarations (gf)
  (slot-value gf 'declarations))

(defun method-qualifiers (method) (slot-value method 'qualifiers))

(defun closer-mop:method-specializers (method)
  (slot-value method 'specializers))

(defun closer-mop:method-lambda-list (method) (slot-value method 'lambda-list))

(defun closer-mop:method-function (method) (slot-value method 'function))

(defun closer-mop:method-generic-function (method)
  (slot-value method 'generic-function))

(defun closer-mop:intern-eql-specializer (object)
  (let ((hit (assoc object %gf-eql-specializers% :test #'eql)))
    (if hit
        (cdr hit)
        (let ((specializer
               (make-instance 'closer-mop:eql-specializer :object object)))
          (setq %gf-eql-specializers%
                (cons (cons object specializer) %gf-eql-specializers%))
          specializer))))

(defun closer-mop:eql-specializer-object (specializer)
  (slot-value specializer 'object))

;; Calling a method: a method metaobject's function takes the argument list and
;; the next methods (AMOP); a plain function in that list is a next method of
;; the compiled chain, called on the arguments.
(defun %gf-call-method (method args next-methods)
  (if (functionp method)
      (apply method args)
      (funcall (slot-value method 'function) args next-methods)))

(defun %gf-next-method (next)
  ;; The rest of the effective method as a method metaobject, the next methods'
  ;; element type a method function may take apart (method-function).
  (make-instance 'standard-method
                 :function (lambda (args next-methods) (apply next args))))

(defun %gf-call-next (next-methods args cnm-args)
  (if next-methods
      (%gf-call-method (car next-methods) (if cnm-args cnm-args args)
                       (cdr next-methods))
      (error "There is no next method.")))

(defun %gf-method-function (lambda-expression)
  ;; The method function of a lambda expression (closer-mop's make-method-lambda
  ;; shape): call-next-method and next-method-p are bound over the next methods
  ;; where the lambda expression names them. Its own variables are gensyms and
  ;; %gf-call-next goes in as a function object: the definition spells no symbol
  ;; of this runtime, whose names the compile paths would read in the program's
  ;; package where they precompile it (CompiledTemplates).
  (let ((args (gensym "ARGS"))
        (next (gensym "NEXT"))
        (cnm-args (gensym "CNM-ARGS")))
    (compile nil
             (list 'lambda (list args next)
                   (if (%gf-names-next-method-p lambda-expression)
                       (list 'flet
                        (list (list 'call-next-method (list '&rest cnm-args)
                                    (list 'funcall #'%gf-call-next next args
                                          cnm-args))
                              (list 'next-method-p nil (list 'if next t nil)))
                        (list 'apply (list 'function lambda-expression) args))
                       (list 'apply (list 'function lambda-expression)
                             args))))))

(defun %gf-names-next-method-p (form)
  (if (consp form)
      (if (%gf-names-next-method-p (car form))
          t
          (%gf-names-next-method-p (cdr form)))
      (if (member form '(call-next-method next-method-p)) t nil)))

(defun %gf-static-method-function (function)
  ;; The method function of a method a defmethod defined: its compiled body
  ;; takes its next method first.
  (lambda (args next-methods)
    (apply function
           (if next-methods
               (lambda (&rest next-args)
                 (%gf-call-method (car next-methods) next-args
                                  (cdr next-methods)))
               nil) args)))

;; Precedence keys of a run-time method's specializers, on the scale of
;; LispMacroExpander.dynamicMethodEntry: 0 for an eql specializer, 10000 plus the
;; class's position in the argument's class precedence list, the built-in rank
;; times 1000 for a built-in class, 1000000 for t; nil when it does not apply.
(defun %gf-specializer-key (specializer arg)
  (if (typep specializer 'closer-mop:eql-specializer)
      (let ((object (slot-value specializer 'object)))
        (if (if (symbolp object) (equal arg object) (eql arg object)) 0 nil))
      (%class-key arg specializer)))

(defun %gf-precedence-indexes (gf)
  ;; The required parameters' indexes in the generic's argument precedence order,
  ;; nil for the leftmost-first order.
  (let ((order (slot-value gf 'argument-precedence-order))
        (required (%gf-required-args (slot-value gf 'lambda-list))))
    (if (and order (= (length order) (length required)))
        (mapcar (lambda (parameter) (position parameter required)) order)
        nil)))

(defun %gf-runtime-entry (method args order)
  (let ((keys nil) (applicable t) (rest args))
    (dolist (specializer (slot-value method 'specializers))
      (if applicable
          (let ((key (%gf-specializer-key specializer (car rest))))
            (if key (setq keys (cons key keys)) (setq applicable nil))
            (setq rest (cdr rest)))
          nil))
    (if applicable
        (cons (car (slot-value method 'qualifiers))
              (cons (lambda (next &rest call-args)
                      (funcall (slot-value method 'function) call-args
                               (if next (list (%gf-next-method next)) nil)))
                    (let ((keys (reverse keys)))
                      ;; The order %dynamic-dispatch compares the keys in.
                      (if order
                          (mapcar (lambda (index) (nth index keys)) order)
                          keys))))
        nil)))

(defun %gf-runtime-entries (methods args order)
  ;; One entry per run-time method, nil for an inapplicable one; ORDER is the
  ;; generic's argument precedence order as indexes (nil: leftmost first).
  (let ((entries nil))
    (dolist (method methods)
      (setq entries (cons (%gf-runtime-entry method args order) entries)))
    (reverse entries)))

(defun %gf-same-specializers-p (a b)
  (let ((same (= (length a) (length b))))
    (loop while (and same a)
          do (if (eq (car a) (car b)) nil (setq same nil)) (setq a (cdr a))
            (setq b (cdr b)))
    same))

(defun %gf-find-method (gf qualifiers specializers)
  (let ((hit nil))
    (dolist (method (slot-value gf 'methods))
      (if (and (null hit) (equal (slot-value method 'qualifiers) qualifiers)
               (%gf-same-specializers-p (slot-value method 'specializers)
                                        specializers))
          (setq hit method)
          nil))
    hit))

(defun %gf-table-add (key method)
  (let ((entry (assoc key %gf-runtime-method-table% :test #'equal)))
    (if entry
        (setf (cdr entry) (append (cdr entry) (list method)))
        (setq %gf-runtime-method-table%
              (cons (list key method) %gf-runtime-method-table%)))))

(defun %gf-table-remove (key method)
  (let ((entry (assoc key %gf-runtime-method-table% :test #'equal)))
    (if entry
        (progn
          (setf (cdr entry) (remove method (cdr entry) :test #'eq))
          (if (cdr entry)
              nil
              (setq %gf-runtime-method-table%
                    (remove entry %gf-runtime-method-table% :test #'eq))))
        nil)))

(defgeneric add-method (generic-function method))

(defmethod add-method ((gf standard-generic-function) (method standard-method))
  ;; AMOP: a method with the same qualifiers and specializers is replaced, the
  ;; method joins the generic, and -- one made at run time -- its run-time table.
  (let ((old
         (%gf-find-method gf (slot-value method 'qualifiers)
                          (slot-value method 'specializers))))
    (if (and old (not (eq old method))) (remove-method gf old) nil))
  (setf (slot-value method 'generic-function) gf)
  (if (member method (slot-value gf 'methods) :test #'eq)
      nil
      (setf (slot-value gf 'methods) (cons method (slot-value gf 'methods))))
  (if (slot-value method '%static-key)
      nil
      (%gf-table-add (%gf-key (slot-value gf 'name)) method))
  gf)

(defgeneric remove-method (generic-function method))

(defmethod remove-method
    ((gf standard-generic-function) (method standard-method))
  ;; A method a defmethod defined stays in its compiled dispatcher: only its
  ;; metaobject leaves the generic.
  (setf (slot-value gf 'methods)
        (remove method (slot-value gf 'methods) :test #'eq))
  (if (slot-value method '%static-key)
      nil
      (%gf-table-remove (%gf-key (slot-value gf 'name)) method))
  (setf (slot-value method 'generic-function) nil)
  gf)

(defun %gf-specializer-designator (specializer)
  ;; find-method's specializer designators: a class, a class name, (eql object).
  (cond ((symbolp specializer) (find-class specializer))
        ((consp specializer)
         (closer-mop:intern-eql-specializer (car (cdr specializer))))
        (t specializer)))

(defun find-method (gf qualifiers specializers &optional (errorp t))
  (let ((method
         (%gf-find-method gf qualifiers
                          (mapcar #'%gf-specializer-designator specializers))))
    (if (or method (not errorp))
        method
        (error
         "There is no method on ~S with qualifiers ~S and specializers ~S." gf
         qualifiers specializers))))

(defun closer-mop:ensure-method (gf lambda-expression &key method-class
                                    (qualifiers nil)
                                    (lambda-list nil lambda-list-p)
                                    (specializers nil specializers-p))
  ;; closer-mop: a method made from a lambda expression and added to the
  ;; generic function.
  (let* ((lambda-list
          (if lambda-list-p lambda-list (car (cdr lambda-expression))))
         (specializers
          (if specializers-p
              specializers
              (mapcar (lambda (parameter) (find-class t))
                      (%gf-required-args lambda-list))))
         (method
          (make-instance (%gf-class-designator
                          (if method-class
                              method-class
                              (slot-value gf 'method-class)))
                         :qualifiers qualifiers
                         :lambda-list lambda-list
                         :specializers specializers
                         :function (%gf-method-function lambda-expression))))
    (add-method gf method)
    method))

(defun %gf-static-method-added
    (name qualifiers specializers function lambda-list static-key)
  ;; A defmethod defined this method on a generic whose defgeneric named a
  ;; generic-function class: its metaobject reaches add-method.
  (let* ((gf (%gf-ensure name))
         (method
          (make-instance (%gf-class-designator (slot-value gf 'method-class))
                         :qualifiers qualifiers
                         :specializers specializers
                         :lambda-list lambda-list
                         :function (%gf-static-method-function function))))
    (setf (slot-value method '%static-key) static-key)
    (add-method gf method)
    method))

(defmethod print-object ((gf standard-generic-function) stream)
  (print-unreadable-object (gf stream :type t :identity t)
    (prin1 (slot-value gf 'name) stream)))

(defmethod print-object ((method standard-method) stream)
  (print-unreadable-object (method stream :type t :identity t)
    (prin1 (slot-value method 'qualifiers) stream)))
