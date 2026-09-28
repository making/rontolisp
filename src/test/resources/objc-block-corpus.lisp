;; The new objc base's blocks, headless: blocks made from Lisp functions and called from
;; Lisp and from Foundation, C functions through fli:define-foreign-function, a block on a
;; serial dispatch queue and an NSURLSession completion handler. Every line prints
;; something deterministic (no address). The interpreter and a JVM class print the same
;; file (objc-block-corpus.expected: ObjcBlockTest, JvmObjcBaseCompilerTest); a --native
;; executable prints objc-block-corpus-native.expected (NativeObjcE2eTest), which differs
;; exactly where a block arrives on another thread: there the module cannot run, so a
;; void block waits for thread 0's event loop (the program's sleep) and runs on thread 0.

(defpackage :objc-block-corpus (:use :cl :objc))
(in-package :objc-block-corpus)

(defun show (label value) (format t "~a: ~s~%" label value))

(defun refused (thunk)
  (handler-case (progn (funcall thunk) :no-error)
    (error (e) (princ-to-string e))))

(fli:define-foreign-function (pthread-main-np "pthread_main_np") ()
  :result-type :int)

(fli:define-foreign-function (dispatch-queue-create "dispatch_queue_create")
    ((label objc-c-string) (attributes :pointer))
  :result-type objc-object-pointer)

(fli:define-foreign-function (dispatch-async "dispatch_async")
    ((queue objc-object-pointer) (work objc-at-question-mark))
  :result-type :void)

(fli:define-foreign-function (dispatch-sync "dispatch_sync")
    ((queue objc-object-pointer) (work objc-at-question-mark))
  :result-type :void)

;; Which thread the code calling this runs on.
(defun thread () (if (= (pthread-main-np) 1) :main :other))

;; Sleeps in short turns until THUNK answers true, at most five seconds.
(defun await (thunk)
  (do ((i 0 (+ i 1))) ((or (funcall thunk) (>= i 500)) (funcall thunk))
    (sleep 0.01)))

;;; a block's life

(defvar *add* (make-objc-block '(:int (:int :int)) (lambda (a b) (+ a b))))
(show "block" *add*)
(show "typep" (typep *add* 'objc-block))
(show "live" (objc-block-live-p *add*))
(show "pointer" (integerp (objc-block-pointer *add*)))
(show "call" (call-objc-block '(:int (:int :int)) *add* 3 4))
(show "free" (free-objc-block *add*))
(show "after free" (list *add* (objc-block-live-p *add*) (objc-block-pointer *add*)))
(show "free twice" (free-objc-block *add*))
(show "call a freed block"
      (refused (lambda () (call-objc-block '(:int (:int :int)) *add* 1 2))))
(show "send a freed block"
      (refused (lambda () (invoke (invoke "NSArray" "array") "enumerateObjectsUsingBlock:" *add*))))

;;; signatures

(define-objc-block-type binary-op :double (:double :double))
(show "define-objc-block-type" (define-objc-block-type unary-op :double (:double)))

(with-objc-block (b 'binary-op (lambda (x y) (* x y)))
  (show "a named type" (call-objc-block 'binary-op b 1.5d0 4)))

(with-objc-block (b '(cocoa:ns-rect (cocoa:ns-rect :double))
                    (lambda (r k) (map 'vector (lambda (v) (* v k)) r)))
  (show "a structure in and out"
        (call-objc-block '(cocoa:ns-rect (cocoa:ns-rect :double)) b #(1 2 3 4) 2)))

(with-objc-block (b '(:boolean (objc-object-pointer))
                    (lambda (s) (> (invoke s "length") 3)))
  (show "a boolean"
        (list (call-objc-block '(:boolean (objc-object-pointer)) b "abcd")
              (call-objc-block '(:boolean (objc-object-pointer)) b "ab"))))

(with-objc-block (b '(objc-object-pointer (objc-object-pointer))
                    (lambda (s) (invoke s "uppercaseString")))
  (show "an object in and out"
        (ns-string-to-string
         (call-objc-block '(objc-object-pointer (objc-object-pointer)) b "shout"))))

(with-objc-block (b '((:unsigned :long-long) ((:unsigned :long-long)))
                    (lambda (n) (- n 1)))
  (show "an unsigned 64-bit value"
        (call-objc-block '((:unsigned :long-long) ((:unsigned :long-long))) b
                         18446744073709551615)))

(show "an unknown type" (refused (lambda () (make-objc-block 'no-such-type #'list))))
(show "not a function" (refused (lambda () (make-objc-block '(:void ()) 42))))
(show "arity" (with-objc-block (b '(:void (:int)) (lambda (x) x))
                (refused (lambda () (call-objc-block '(:void (:int)) b)))))

;; A function that signals is contained: printed, and the block answers zero.
(with-objc-block (b '(:int (:int)) (lambda (x) (error "no ~a" x)))
  (show "an error inside" (call-objc-block '(:int (:int)) b 9)))

;;; Foundation calls blocks on the thread that sent to it

(defvar *words* (invoke "NSArray" "arrayWithObjects:" "pear" "fig" "apple"))

(let ((seen nil))
  (with-objc-block (b '(:void (objc-object-pointer (:unsigned :long-long)
                               (:pointer objc-c++-bool)))
                      (lambda (object index stop)
                        (declare (ignore stop))
                        (push (list index (ns-string-to-string object) (thread))
                              seen)))
    (invoke *words* "enumerateObjectsUsingBlock:" b))
  (show "enumerateObjectsUsingBlock:" (reverse seen)))

(with-objc-block (b '(:long-long (objc-object-pointer objc-object-pointer))
                    (lambda (x y)
                      (let ((a (ns-string-to-string x)) (b (ns-string-to-string y)))
                        (cond ((string< a b) -1) ((string> a b) 1) (t 0)))))
  (show "sortedArrayUsingComparator:"
        (invoke-into '(array string) *words* "sortedArrayUsingComparator:" b)))

(with-objc-block (b '(:boolean (objc-object-pointer (:unsigned :long-long)
                                (:pointer objc-c++-bool)))
                    (lambda (object index stop)
                      (declare (ignore index stop))
                      (> (invoke object "length") 3)))
  (show "indexesOfObjectsPassingTest:"
        (invoke (invoke *words* "indexesOfObjectsPassingTest:" b) "count")))

(let ((words nil))
  (with-objc-block (b '(:void (objc-object-pointer cocoa:ns-range cocoa:ns-range
                               (:pointer objc-c++-bool)))
                      (lambda (word range enclosing stop)
                        (declare (ignore enclosing stop))
                        (push (list (ns-string-to-string word) range) words)))
    ;; NSStringEnumerationByWords
    (invoke (string-to-ns-string "hello block world")
            "enumerateSubstringsInRange:options:usingBlock:" (cons 0 17) 3 b))
  (show "structures into a block" (reverse words)))

(show "a function where a block goes"
      (refused (lambda () (invoke *words* "enumerateObjectsUsingBlock:" #'list))))

;;; C functions

(fli:define-foreign-function (no-such "rontolisp_no_such_function") ())

(show "pthread_main_np" (integerp (pthread-main-np)))
(show "no such function" (refused (lambda () (no-such))))

;;; libdispatch: a serial queue

(defvar *queue* (dispatch-queue-create "rontolisp.block-corpus" nil))
(defvar *async* nil)

;; The block is freed when dispatch_async returns: the queue keeps a copy, and the copy
;; keeps the function.
(with-objc-block (b '(:void ()) (lambda () (setq *async* (list :ran (thread)))))
  (dispatch-async *queue* b))
;; A serial queue runs in order: once dispatch_sync returns, the async block has run --
;; or, on --native, arrived and waits for thread 0.
(let ((sync nil))
  (with-objc-block (b '(:void ()) (lambda () (setq sync (thread))))
    (dispatch-sync *queue* b))
  (show "dispatch_sync ran on" sync))
(show "dispatch_async before the wait" *async*)
(show "dispatch_async after the wait" (await (lambda () *async*)))

;;; NSURLSession: a completion handler

(defvar *response* nil)

(with-objc-block (handler '(:void (objc-object-pointer objc-object-pointer
                                   objc-object-pointer))
                          (lambda (data response error)
                            (setq *response*
                                  (list (invoke-into 'string
                                                     (invoke (invoke "NSString" "alloc")
                                                             "initWithData:encoding:"
                                                             data 4)
                                                     "description")
                                        (invoke-into 'string response "MIMEType")
                                        error (thread)))))
  (invoke (invoke (invoke "NSURLSession" "sharedSession")
                  "dataTaskWithURL:completionHandler:"
                  (invoke "NSURL" "URLWithString:" "data:text/plain,hello%20blocks")
                  handler)
          "resume"))
(show "a completion handler" (await (lambda () *response*)))
