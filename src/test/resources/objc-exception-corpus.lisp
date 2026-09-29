;; The new objc base's failures that Objective-C reports itself, headless: an NSException
;; raised inside a send is the condition objc:objc-exception, signalled in the innermost
;; invoke, and objc:invoke-with-error turns an NSError ** a method writes on failure into
;; objc:ns-error. Every line prints something deterministic (no address, no localized
;; text). The interpreter, a JVM class and a --native executable print the same file
;; (objc-exception-corpus.expected: ObjcExceptionTest, JvmObjcBaseCompilerTest,
;; NativeObjcE2eTest).

(defpackage :objc-exception-corpus (:use :cl :objc))
(in-package :objc-exception-corpus)

(defun show (label value) (format t "~a: ~s~%" label value))

(defun refused (thunk)
  (handler-case (progn (funcall thunk) :no-error)
    (error (e) (princ-to-string e))))

;;; an exception inside a send

(print (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
         (error (e) (list :caught (princ-to-string e)))))
(print :after)
(terpri)

(defun out-of-range ()
  (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
    (objc-exception (e) e)))

(let ((e (out-of-range)))
  (show "type" (list (typep e 'objc-exception) (typep e 'error)))
  (show "name" (objc-exception-name e))
  (show "reason" (objc-exception-reason e))
  (show "object" (typep (objc-exception-object e) 'objc-object-pointer))
  (show "object's name" (invoke-into 'string (objc-exception-object e) "name"))
  (show "object's retain count" (retain-count (objc-exception-object e))))

;; A class method, and an exception with a name of the program's own.
(show "class send"
      (refused (lambda () (invoke "NSString" "stringWithUTF8String:" nil))))
(show "raise"
      (handler-case
          (invoke (invoke "NSException" "exceptionWithName:reason:userInfo:"
                          "CorpusException" "made here" nil)
                  "raise")
        (objc-exception (e) (list (objc-exception-name e) (objc-exception-reason e)))))

;; Something thrown that is not an NSException: the name is its class, and it has no
;; reason.
(fli:define-foreign-function (objc-exception-throw "objc_exception_throw")
    ((object objc-object-pointer))
  :result-type :void)

(show "thrown string"
      (handler-case (objc-exception-throw
                     (invoke "NSMutableString" "stringWithString:" "not an exception"))
        (objc-exception (e)
          (list (objc-exception-name e) (objc-exception-reason e)
                (invoke-into 'string (objc-exception-object e) "description")))))

;; The frames Lisp owns unwind as for any error; sends go on working.
(defvar *cleaned* nil)
(show "unwind-protect"
      (list (refused (lambda ()
                       (unwind-protect (invoke (invoke "NSArray" "array") "objectAtIndex:" 0)
                         (setq *cleaned* t))))
            *cleaned*))
(show "inside a pool"
      (refused (lambda ()
                 (with-autorelease-pool ()
                   (invoke (invoke "NSMutableArray" "array") "removeObjectAtIndex:" 3)))))
(show "a send after" (invoke (invoke "NSArray" "arrayWithObject:" "x") "count"))
(show "many"
      (let ((n 0))
        (dotimes (i 200 n)
          (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" i)
            (objc-exception () (setq n (+ n 1)))))))

;; Inside a method defined in Lisp: the innermost invoke is the method's own. One the
;; method lets escape is a Lisp error inside a callback: printed and answered as zero,
;; never unwound through Objective-C's frames.
(define-objc-class corpus-raiser ()
  ()
  (:objc-class-name "RontolispExceptionCorpusRaiser"))

(define-objc-method ("probe" :int) ((self corpus-raiser))
  (handler-case (progn (invoke (invoke "NSArray" "array") "objectAtIndex:" 2) 1)
    (objc-exception () -1)))

(define-objc-method ("escape" :void) ((self corpus-raiser))
  (invoke (invoke "NSArray" "array") "objectAtIndex:" 9))

(let ((raiser (make-instance 'corpus-raiser)))
  (show "caught in the method" (invoke raiser "probe"))
  (show "escaping the method"
        (refused (lambda () (invoke raiser "escape"))))
  (release (objc-object-pointer raiser)))

;;; invoke-with-error

(defvar *files* (invoke "NSFileManager" "defaultManager"))

(defun ns-error-of (thunk)
  (handler-case (progn (funcall thunk) :no-error)
    (ns-error (e)
      (list (ns-error-domain e) (ns-error-code e) (stringp (ns-error-description e))
            (invoke-into 'string (ns-error-object e) "domain")))))

(show "object result, failed"
      (ns-error-of (lambda ()
                     (invoke-with-error *files* "attributesOfItemAtPath:error:"
                                        "/no/such/rontolisp/path"))))
(show "object result, succeeded"
      (typep (invoke-with-error *files* "attributesOfItemAtPath:error:" "/")
             'objc-object-pointer))
(show "BOOL result, failed"
      (ns-error-of (lambda ()
                     (invoke-with-error *files* "removeItemAtPath:error:"
                                        "/no/such/rontolisp/path"))))
(show "class method, failed"
      (ns-error-of (lambda ()
                     (invoke-with-error "NSString" "stringWithContentsOfFile:encoding:error:"
                                        "/no/such/rontolisp/path" 4))))
;; Cocoa spells the NSError ** piece "error:" or, after another word, "Error:"
;; (startAndReturnError:, checkResourceIsReachableAndReturnError:).
(show "an AndReturnError: method, failed"
      (ns-error-of (lambda ()
                     (invoke-with-error (invoke "NSURL" "fileURLWithPath:" "/no/such/rontolisp/path")
                                        "checkResourceIsReachableAndReturnError:"))))
(show "an AndReturnError: method, succeeded"
      (invoke-with-error (invoke "NSURL" "fileURLWithPath:" "/")
                         "checkResourceIsReachableAndReturnError:"))
(show "a condition"
      (handler-case (invoke-with-error *files* "removeItemAtPath:error:" "/no/such/rontolisp/path")
        (error (e) (list (typep e 'ns-error) (typep e 'error)))))
(show "not an error method"
      (refused (lambda () (invoke-with-error *files* "attributesOfItemAtPath:" "/"))))
(show "no such method"
      (handler-case (invoke-with-error *files* "noSuchThing:error:" 1)
        (error (e) (list (typep e 'error) (typep e 'objc-exception) (typep e 'ns-error)))))
