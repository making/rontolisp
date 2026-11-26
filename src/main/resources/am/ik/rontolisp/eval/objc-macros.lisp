;; The defining macros of the new objc base: LispWorks 8.1's define-objc-class,
;; define-objc-method, define-objc-class-method, define-objc-struct, define-objc-typedef,
;; define-objc-protocol and current-super. Each expands into a call of objc-class.lisp,
;; which does the work at run time on every target; the expansions quote their type
;; descriptors, so nothing here needs the runtime (the manual: the defining macros may
;; run before ensure-objc-initialized).
;;
;; A separate file because the compile path expands macros BEFORE it splices the
;; libraries (ObjcLibrary.macroForms, CompileFrontend); the interpreter loads it with
;; objc.lisp. Macro bodies use nothing but cl: they run in the macro-time evaluator.

(defmacro objc:define-objc-class (name superclasses slots &rest options)
  (let ((objc-name nil)
        (objc-super nil)
        (ivars nil)
        (protocols nil)
        (others nil))
    (dolist (option options)
      (cond
       ((eq (car option) :objc-class-name) (setq objc-name (second option)))
       ((eq (car option) :objc-superclass-name)
        (setq objc-super (second option)))
       ((eq (car option) :objc-instance-vars) (setq ivars (cdr option)))
       ((eq (car option) :objc-protocols) (setq protocols (cdr option)))
       (t (push option others))))
    `(progn
       (defclass ,name ,(or superclasses '(objc:standard-objc-object))
         ,slots
         ,@(reverse others))
       (objc::%define-objc-class ',name ',superclasses ,objc-name ,objc-super
                                 ',ivars ',protocols)
       ',name)))

;; (name result-type [result-style]) ((object-var class-name [pointer-var]) argspec*)
;; body: the body becomes a function of the super reference, the object, the pointer,
;; the result structure and the arguments, which objc-class.lisp converts and calls.
;; (name result-type [result-style]) ((object-var class-name [pointer-var]) argspec*)
;; body: the body becomes a function of the super reference, the object, the pointer,
;; the result structure and the list of converted arguments, which objc-class.lisp
;; calls (one list, not a parameter each: a wasm lambda takes at most ten).
(defmacro objc::%define-method
    (class-method-p name-spec object-spec argspecs body)
  (let* ((name (first name-spec))
         (result-type (second name-spec))
         (result-style (third name-spec))
         (result-var
          (if (and result-style (symbolp result-style)
                   (not (keywordp result-style)))
              result-style
              nil))
         (object-var (first object-spec))
         (class-name (second object-spec))
         (pointer-var (or (third object-spec) (gensym "POINTER")))
         (result (or result-var (gensym "RESULT")))
         (arguments (gensym "ARGUMENTS"))
         (index -1))
    `(objc::%define-objc-method ',class-name ,name ',result-type
      ',(if result-var nil result-style) ,(if result-var t nil)
      ',(mapcar (lambda (spec) (list (second spec) (third spec))) argspecs)
      ,class-method-p
      (lambda (objc::%current-super ,object-var ,pointer-var ,result ,arguments)
        (declare
         (ignorable objc::%current-super ,object-var ,pointer-var ,result
                    ,arguments))
        (let ,(mapcar
               (lambda
                (spec)
                (setq index (+ index 1))
                (list (first spec) (list 'nth index arguments)))
               argspecs)
          ,@body)))))

(defmacro objc:define-objc-method (name-spec lambda-list &body body)
  `(objc::%define-method nil ,name-spec ,(car lambda-list) ,(cdr lambda-list)
                         ,body))

(defmacro objc:define-objc-class-method (name-spec lambda-list &body body)
  `(objc::%define-method t ,name-spec ,(car lambda-list) ,(cdr lambda-list)
                         ,body))

;; Only inside a method body, where the method's lambda binds the variable.
(defmacro objc:current-super () 'objc::%current-super)

(defmacro objc:define-objc-struct (name-and-options &body slots)
  (let ((name
         (if (consp name-and-options) (car name-and-options) name-and-options))
        (options (if (consp name-and-options) (cdr name-and-options) nil)))
    `(progn
       (objc::%define-objc-struct ',name ',options ',slots)
       ',name)))

(defmacro objc:define-objc-typedef (name-and-options &optional type)
  (let ((name
         (if (consp name-and-options) (car name-and-options) name-and-options))
        (options (if (consp name-and-options) (cdr name-and-options) nil)))
    `(progn
       (objc::%define-objc-typedef ',name ',options ',type)
       ',name)))

(defmacro objc:define-objc-protocol
    (name &key incorporated-protocols instance-methods class-methods)
  `(objc::%define-objc-protocol ,name ',incorporated-protocols
                                ',instance-methods ',class-methods))

;; The blocks half (objc-block.lisp): a block signature named once, and a block alive for
;; the extent of a body -- a callee that keeps the block has copied it by then, and the
;; copy holds the function until the last copy is disposed of.
(defmacro objc:define-objc-block-type (name result-type argument-types)
  `(objc::%define-objc-block-type ',name ',result-type ',argument-types))

(defmacro objc:with-objc-block ((var type function) &body body)
  `(let ((,var (objc:make-objc-block ,type ,function)))
     (unwind-protect (progn ,@body) (objc:free-objc-block ,var))))

;; LispWorks' fli:define-foreign-function, the part C functions called beside the objc
;; base need: (lisp-name [foreign-name]) or lisp-name, arguments (name type) or
;; (:constant value type), :result-type (:int by default), :module, and
;; :variadic-num-of-fixed. A foreign name left out is the Lisp name in lower case with
;; each hyphen an underscore. The types are the ones objc:invoke's list form takes.
(defmacro fli:define-foreign-function (name args &key (result-type :int) module
                                            variadic-num-of-fixed documentation
                                            language calling-convention no-check
                                            lambda-list)
  (declare (ignore documentation language calling-convention no-check))
  (when lambda-list
    (error "fli:define-foreign-function: :lambda-list is not supported"))
  (let* ((lisp-name (if (consp name) (first name) name))
         (foreign-name
          (if (and (consp name) (second name))
              (second name)
              (substitute #\_ #\- (string-downcase (symbol-name lisp-name)))))
         (parameters nil)
         (forms nil)
         (types nil))
    (unless (stringp foreign-name)
      (error
       "fli:define-foreign-function: the foreign name must be a string, got ~s"
       foreign-name))
    (dolist (arg args)
      (cond ((and (consp arg) (eq (first arg) :constant))
             (push (list 'quote (second arg)) forms)
             (push (third arg) types))
            ((and (consp arg) (symbolp (first arg)) (consp (cdr arg)))
             (push (first arg) parameters)
             (push (first arg) forms)
             (push (second arg) types))
            (t (error "fli:define-foreign-function: an argument is (name type) or (:constant value type), got ~s"
                      arg))))
    `(progn
       (defun ,lisp-name ,(reverse parameters)
         (objc::%foreign-call ,foreign-name ',(reverse types) ',result-type
                              ,module ,variadic-num-of-fixed
                              (list ,@(reverse forms))))
       ',lisp-name)))

;; LispWorks' fli:with-dynamic-foreign-objects: each binding (var type &key nelems
;; initial-element initial-contents fill) is a foreign object alive for the body --
;; allocated before it and freed however it is left. LispWorks takes the memory from the
;; stack; here it is the host's heap (objc.lisp's fli:allocate-foreign-object).
(defmacro fli:with-dynamic-foreign-objects (bindings &body body)
  (if (null bindings)
      `(progn ,@body)
      (let* ((binding (car bindings))
             (var (first binding))
             (options (cddr binding))
             (keys nil))
        (unless (and var (symbolp var) (consp (cdr binding)))
          (error "fli:with-dynamic-foreign-objects: a binding is (var type &key ...), got ~s"
                 binding))
        (do ((rest options (cddr rest)))
            ((null rest))
          (unless (eq (car rest) :size-slot)
            (push (car rest) keys)
            (push (cadr rest) keys)))
        `(let ((,var
                (fli:allocate-foreign-object :type ',(second binding)
                                             ,@(reverse keys))))
           (unwind-protect (fli:with-dynamic-foreign-objects ,(cdr bindings)
                             ,@body)
             (fli:free-foreign-object ,var))))))
