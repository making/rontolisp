;; The new objc base, headless: the manual's call-side examples (invoking, strings,
;; memory management, the Foundation structures) written as the manual writes them --
;; in a package that uses objc -- plus the conversions, the ownership rule, the value
;; identity and the refusals around them. Every line prints something deterministic (no
;; address), so the interpreter's output is what a JVM class and a --native executable
;; must print byte for byte (ObjcBaseTest, JvmObjcBaseCompilerTest, NativeObjcE2eTest).

(defpackage :objc-base-corpus (:use :cl :objc))
(in-package :objc-base-corpus)

(defun show (label value) (format t "~a: ~s~%" label value))

(defun refused (thunk)
  (handler-case (progn (funcall thunk) :no-error)
    (error (e)
      ;; An address in a message varies from run to run.
      (let* ((text (princ-to-string e)) (at (search "#x" text)))
        (if at (concatenate 'string (subseq text 0 at) "#x..." (subseq text (+ at 18))) text)))))

;;; 1.1.1 Initialization
(show "ensure-objc-initialized"
      (ensure-objc-initialized
       :modules
       '("/System/Library/Frameworks/Foundation.framework/Versions/C/Foundation"
         "/System/Library/Frameworks/Cocoa.framework/Versions/A/Cocoa")))

;;; 1.3.1 - 1.3.3 invoke, method naming, special argument and result conversion
(defvar *hello* (invoke "NSString" "stringWithUTF8String:" "hello world"))
(show "length" (invoke *hello* "length"))
(show "rangeOfString:" (invoke *hello* "rangeOfString:" "world"))
(show "rangeOfString: not found" (invoke *hello* "rangeOfString:" "moon"))
(show "not found is ns-not-found" (= (car (invoke *hello* "rangeOfString:" "moon")) cocoa:ns-not-found))
(show "substringWithRange:" (invoke-into 'string *hello* "substringWithRange:" '(6 . 5)))
(defvar *view* (objc:invoke (objc:invoke "NSScrollView" "alloc")
                            "initWithFrame:" #(0 0 100 100)))
(show "frame" (invoke *view* "frame"))
(show "Class argument" (invoke-bool *view* "isKindOfClass:" "NSView"))
(show "BOOL argument t" (progn (invoke *view* "setHidden:" t) (invoke *view* "isHidden")))
(show "BOOL argument nil" (progn (invoke *view* "setHidden:" nil) (invoke *view* "isHidden")))
(show "id argument vector"
      (invoke-into 'string (invoke "NSArray" "arrayWithArray:" #("a" "b" "c"))
                   "componentsJoinedByString:" "-"))
(show "char * result" (invoke *hello* "UTF8String"))
(show "unsigned 64" (invoke (invoke "NSNumber" "numberWithUnsignedLongLong:" 18446744073709551615)
                            "unsignedLongLongValue"))
(show "double" (invoke (invoke "NSNumber" "numberWithDouble:" 2.5d0) "doubleValue"))
(show "nil receiver" (invoke nil "length"))

;;; 1.3.4 a method that returns a boolean
(show "invoke on BOOL" (invoke *hello* "hasPrefix:" "hello"))
(show "invoke on BOOL false" (invoke *hello* "hasPrefix:" "zzz"))
(show "invoke-bool" (if (invoke-bool *hello* "hasPrefix:" "hello") 1 2))

;;; 1.3.5 a method that returns a structure
(show "invoke-into vector"
      (let ((rect (make-array 4)))
        (objc:invoke-into rect *view* "frame")
        rect))
(show "invoke-into cons" (let ((range (cons 0 0))) (invoke-into range *hello* "rangeOfString:" "o")))
(show "NSValue round trip" (invoke (invoke "NSValue" "valueWithRect:" #(1 2 3 4)) "rectValue"))
(show "NSPoint" (invoke (invoke "NSValue" "valueWithPoint:" #(5 6)) "pointValue"))
(show "NSSize" (invoke (invoke "NSValue" "valueWithSize:" #(7 8)) "sizeValue"))

;;; 1.3.6 a method that returns a string or array
(show "description pointer" (typep (invoke *hello* "description") 'objc-object-pointer))
(show "description string" (invoke-into 'string *hello* "description"))
(show "array" (length (invoke-into 'array (invoke "NSArray" "arrayWithArray:" #("x" "y")) "self")))
(show "(array string)" (invoke-into '(array string) (invoke "NSArray" "arrayWithArray:" #("x" "y")) "self"))
(show "(array (array string))"
      (invoke-into '(array (array string)) (invoke "NSArray" "arrayWithArray:" #(#("p" "q"))) "self"))
(show "vector result"
      (map 'list (lambda (e) (typep e 'objc-object-pointer))
           (invoke-into (make-array 2 :initial-element nil) (invoke "NSArray" "arrayWithArray:" #("x")) "self")))

;;; 1.3.9 a variadic method
(show "variadic list form"
      (invoke-into 'string "NSString"
                   '("stringWithFormat:"
                     (objc:objc-object-pointer :int)
                     :result-type objc:objc-object-pointer
                     :variadic-num-of-fixed 1)
                   "The integer %d"
                   42))
(show "variadic known selector"
      (invoke-into 'string "NSString" "stringWithFormat:" "%@ and %ld and %.1f" "x" 42 1.5d0))
(show "nil-terminated" (invoke (invoke "NSArray" "arrayWithObjects:" "a" "b" "c") "count"))

;;; 1.3.10 whether a method exists
(show "can-invoke-p" (can-invoke-p *view* "frame"))
(show "can-invoke-p class" (can-invoke-p "NSString" "stringWithUTF8String:"))
(show "can-invoke-p missing" (can-invoke-p *hello* "noSuchMethodAtAll"))

;;; 1.3.11 memory management
(defvar *object* (alloc-init-object "NSObject"))
(show "retain-count of a new object" (retain-count *object*))
(show "retain answers its argument" (eq (retain *object*) *object*))
(show "retain-count after retain" (retain-count *object*))
(show "release" (release *object*))
(show "retain-count after release" (retain-count *object*))
(show "autorelease answers its argument"
      (with-autorelease-pool () (eq (autorelease (retain *object*)) *object*)))
(show "retain-count after the pool" (retain-count *object*))
(let ((pool (make-autorelease-pool)))
  (autorelease (retain *object*))
  (show "retain-count inside a made pool" (retain-count *object*))
  (release pool)
  (show "retain-count after releasing the pool" (retain-count *object*)))
(show "invoke release routes to release" (progn (retain *object*) (invoke *object* "release")))
(show "release its reference" (release *object*))
(show "a second release is refused" (refused (lambda () (release *object*))))
(show "invoke release is refused too" (refused (lambda () (invoke *object* "release"))))
(let ((string (string-to-ns-string "owned")))
  (show "string-to-ns-string" (ns-string-to-string string))
  (show "release a string-to-ns-string" (release string)))
(show "string-to-ns-string autoreleased"
      (with-autorelease-pool () (ns-string-to-string (string-to-ns-string "pooled" t))))
(defun object-description (object)
  (with-autorelease-pool ()
    (invoke-into 'string object "description")))
(show "with-autorelease-pool example" (object-description (invoke "NSNumber" "numberWithInt:" 42)))
(show "with-autorelease-pool values" (multiple-value-list (with-autorelease-pool () (values 1 2))))
(show "new is owned" (retain-count (invoke "NSObject" "new")))

;;; 1.3.12 selectors
(defvar *selector* (coerce-to-selector "frame"))
(show "selector-name" (selector-name *selector*))
(show "selector-name string" (selector-name "notAColonName"))
(show "selector interned" (eq *selector* (coerce-to-selector "frame")))
(show "respondsToSelector:" (invoke-bool *view* "respondsToSelector:" (coerce-to-selector "frame")))
(show "SEL argument and result"
      (let ((invocation (invoke "NSInvocation" "invocationWithMethodSignature:"
                                (invoke "NSObject" "instanceMethodSignatureForSelector:" "description"))))
        (invoke invocation "setSelector:" "description")
        (selector-name (invoke invocation "selector"))))

;;; strings (1.2.5)
(show "ns-string-to-string" (ns-string-to-string *hello*))
(show "line terminators"
      (ns-string-to-string (string-to-ns-string (format nil "a~Cb~C~Cc" (code-char 13) (code-char 13)
                                                        (code-char 10)))))
(show "line terminators preserved"
      (length (ns-string-to-string (string-to-ns-string (format nil "a~Cb" (code-char 13))) t)))
(show "unicode" (ns-string-to-string (string-to-ns-string "λ→✓")))
(show "description" (description (invoke "NSNumber" "numberWithInt:" 7)))

;;; classes
(show "coerce-to-objc-class" (objc-class-name (coerce-to-objc-class "NSString")))
(show "class interned" (eq (coerce-to-objc-class "NSObject") (invoke "NSObject" "class")))
(show "class through self" (eq (coerce-to-objc-class "NSObject") (invoke "NSObject" "self")))
(show "class of an object" (objc-class-name (invoke *view* "class")))
(show "unknown class" (refused (lambda () (coerce-to-objc-class "NoSuchClassAnywhere"))))
(show "alloc-init-object" (objc-class-name (invoke (alloc-init-object "NSMutableArray") "class")))

;;; the recorded LispWorks answers (objc-lispworks-answers.lisp)
(show "sig length" (multiple-value-list (objc-class-method-signature "NSString" "length")))
(show "sig rangeOfString:" (multiple-value-list (objc-class-method-signature "NSString" "rangeOfString:")))
(show "sig substringWithRange:"
      (multiple-value-list (objc-class-method-signature "NSString" "substringWithRange:")))
(show "sig description" (multiple-value-list (objc-class-method-signature "NSObject" "description")))
(show "sig missing" (objc-class-method-signature "NSObject" "noSuchMethodAtAll"))
(show "missing method" (refused (lambda () (invoke *hello* "noSuchMethodAtAll"))))

;;; refusals
(show "arity" (refused (lambda () (invoke *hello* "length" 1))))
(show "argument type" (refused (lambda () (invoke *hello* "characterAtIndex:" "x"))))
(show "rect argument" (refused (lambda () (invoke (invoke "NSScrollView" "alloc") "initWithFrame:" '(0 0 1 1)))))
(show "range argument" (refused (lambda () (invoke *hello* "substringWithRange:" #(1 2)))))
(show "receiver" (refused (lambda () (invoke 42 "length"))))

;;; identity and type
(let* ((s (invoke *hello* "self"))
       (equal-table (make-hash-table :test 'equal))
       (eq-table (make-hash-table :test 'eq)))
  (setf (gethash *hello* equal-table) :found (gethash *hello* eq-table) :found)
  (show "one object, one value" (list (eq s *hello*) (eql s *hello*) (equal s *hello*) (equalp s *hello*)))
  (show "hash tables" (list (gethash s equal-table) (gethash s eq-table)))
  (show "two objects" (eq (invoke "NSObject" "new") (invoke "NSObject" "new"))))
(show "type-of" (list (type-of *hello*) (type-of (coerce-to-objc-class "NSObject")) (type-of *selector*)))
(show "typep" (list (typep *hello* 'objc-object-pointer) (typep (coerce-to-objc-class "NSObject") 'objc-class)
                    (typep (coerce-to-objc-class "NSObject") 'objc-object-pointer)
                    (typep *hello* 'objc-class) (typep *selector* 'sel)))
(show "no structure-object" (list (typep *hello* 'structure-object) (typep *selector* 'structure-object)))
(show "typecase" (typecase *hello* (objc-class :class) (objc-object-pointer :object) (t :other)))
(show "objc-object-pointer" (eq (objc-object-pointer *hello*) *hello*))
(show "objc-object-from-pointer" (objc-object-from-pointer *hello*))

;;; COCOA
(show "set-ns-point*" (cocoa:set-ns-point* (make-array 2) 1 2))
(show "set-ns-size*" (cocoa:set-ns-size* (make-array 2) 3 4))
(show "set-ns-rect*" (cocoa:set-ns-rect* (make-array 4) 1 2 3 4))
(show "set-ns-range*" (cocoa:set-ns-range* (cons 0 0) 6 5))
(show "a filled rect is an argument"
      (invoke (invoke "NSValue" "valueWithRect:" (cocoa:set-ns-rect* (make-array 4) 9 8 7 6)) "rectValue"))
(show "ns-not-found" cocoa:ns-not-found)

;;; on-main
(show "on-main" (on-main (lambda () (invoke *hello* "length"))))
