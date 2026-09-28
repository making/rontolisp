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
