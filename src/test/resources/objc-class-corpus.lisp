;; The new objc base's class definition, headless: the manual's section 1.4 examples
;; written as the manual writes them -- in a package that uses objc, defined before
;; ensure-objc-initialized as the manual allows -- plus methods of every shape a send can
;; have, super sends, instance variables, the lifecycle generics, an observer and the
;; refusals. Every line prints something deterministic (no address), so the interpreter's
;; output is what a JVM class and a --native executable must print byte for byte
;; (ObjcClassTest, JvmObjcBaseCompilerTest, NativeObjcE2eTest).

(defpackage :objc-class-corpus (:use :cl :objc))
(in-package :objc-class-corpus)

(defun show (label value) (format t "~a: ~s~%" label value))

(defun refused (thunk)
  (handler-case (progn (funcall thunk) :no-error)
    (error (e) (princ-to-string e))))

;;; 1.4 the manual's examples

(define-objc-class my-object ()
  ((slot1 :initarg :slot1 :initform nil))
  (:objc-class-name "MyObject"))

(define-objc-method ("areaOfWidth:height:" (:unsigned :int))
    ((self my-object)
     (width (:unsigned :int))
     (height (:unsigned :int)))
  (* width height))

(define-objc-class my-special-object (my-object)
  ()
  (:objc-class-name "MySpecialObject"))

(define-objc-class my-other-object ()
  ()
  (:objc-class-name "MyOtherObject")
  (:objc-superclass-name "MyObject"))

(define-objc-method ("areaOfWidth:height:" (:unsigned :int))
    ((self my-special-object)
     (width (:unsigned :int))
     (height (:unsigned :int)))
  (* 4 (invoke (current-super) "areaOfWidth:height:"
               width height)))

(define-objc-struct (pair
                     (:foreign-name "_Pair"))
  (:first :float)
  (:second :float))

(define-objc-method ("pair" (:struct pair) result-pair)
    ((this my-object))
  (setf (fli:foreign-slot-value result-pair :first) 1f0
        (fli:foreign-slot-value result-pair :second) 2f0))

;; 1.3.7's getValueInto:, defined here so that the manual's call has a callee.
(define-objc-method ("getValueInto:" :void)
    ((self my-object) (result-value (:pointer :int)))
  (setf (fli:dereference result-value) 42))

(define-objc-class my-size-mixin ()
  ())

(define-objc-method ("size" (:unsigned :int))
    ((self my-size-mixin))
  42)

(define-objc-class my-data (my-size-mixin)
  ()
  (:objc-class-name "MyData"))

(define-objc-class my-other-data (my-size-mixin)
  ()
  (:objc-class-name "MyOtherData"))

(show "ensure-objc-initialized" (ensure-objc-initialized))
(show "areaOfWidth:height:" (invoke (alloc-init-object "MyObject") "areaOfWidth:height:" 6 7))
(show "current-super" (invoke (alloc-init-object "MySpecialObject") "areaOfWidth:height:" 6 7))
(show ":objc-superclass-name" (invoke (alloc-init-object "MyOtherObject") "areaOfWidth:height:" 3 4))
(show "superclass" (objc-class-name (invoke "MyOtherObject" "superclass")))
(show "class pointer identity"
      (eq (objc-object-pointer (find-class 'my-object)) (coerce-to-objc-class "MyObject")))
(show "mixin MyData" (invoke (alloc-init-object "MyData") "size"))
(show "mixin MyOtherData" (invoke (alloc-init-object "MyOtherData") "size"))
(show "unrelated through the mixin" (objc-class-name (invoke "MyData" "superclass")))
(show "a structure result" (invoke (alloc-init-object "MyObject") "pair"))
(show "a structure into a foreign object"
      (fli:with-dynamic-foreign-objects ((pair (:struct pair)))
        (invoke-into pair (alloc-init-object "MyObject") "pair")
        (list (fli:size-of '(:struct pair)) (fli:foreign-slot-value pair :first)
              (fli:foreign-slot-value pair :second))))
(show "1.3.7 a value returned by reference"
      (let ((object (alloc-init-object "MyObject")))
        (fli:with-dynamic-foreign-objects ((result-value :int))
          (objc:invoke object "getValueInto:" result-value)
          (fli:dereference result-value))))
(show "signature of a Lisp method"
      (multiple-value-list (objc-class-method-signature "MyObject" "areaOfWidth:height:")))
(let ((object (make-instance 'my-object :slot1 :hello)))
  (show "make-instance" (slot-value object 'slot1))
  (show "objc-object-from-pointer" (eq object (objc-object-from-pointer (objc-object-pointer object))))
  (show "an instance is a receiver" (invoke object "areaOfWidth:height:" 2 3))
  (show "its class" (objc-class-name (invoke object "class")))
  (show "one pointer per object" (eq (objc-object-pointer object) (invoke object "self")))
  (show "retain-count" (retain-count object))
  (show "release" (release object)))
(show "from-pointer of a class"
      (eq (find-class 'my-object) (objc-object-from-pointer (coerce-to-objc-class "MyObject"))))
(show "from-pointer of a foreign object" (objc-object-from-pointer (invoke "NSObject" "new")))
(let ((object (objc-object-from-pointer (alloc-init-object "MyObject"))))
  (show "Objective-C allocated, Lisp object made" (typep object 'my-object))
  (show "its slots are initialized" (slot-value object 'slot1)))

;;; methods of any shape

(define-objc-class shapes ()
  ((log :initform nil :accessor shapes-log))
  (:objc-class-name "RontoLispCorpusShapes"))

(define-objc-typedef (count-type) (:unsigned :int))

(define-objc-method ("sum:plus:" :double) ((self shapes) (a :double) (b :float))
  (+ a b))

(define-objc-method ("negate:" :long-long) ((self shapes) (n :long-long))
  (- n))

(define-objc-method ("isBig:" objc-bool) ((self shapes) (n :int))
  (> n 100))

(define-objc-method ("flag:" (:unsigned :char)) ((self shapes) (b objc-bool))
  (if b 7 3))

(define-objc-method ("scaled:by:" cocoa:ns-rect) ((self shapes) (rect cocoa:ns-rect) (k :double))
  (map 'vector (lambda (x) (* x k)) rect))

(define-objc-method ("rangeAfter:" cocoa:ns-range result-range) ((self shapes) (range cocoa:ns-range))
  (cocoa:set-ns-range* result-range (+ (car range) (cdr range)) 1))

(define-objc-method ("swapped:" (:struct pair)) ((self shapes) (p (:struct pair)))
  (vector (aref p 1) (aref p 0)))

(define-objc-method ("tally" :int result-count) ((self shapes))
  (setf (fli:dereference result-count) 7))

(defvar *unit-rect* (fli:allocate-foreign-object :type 'cocoa:ns-rect))
(cocoa:set-ns-rect* *unit-rect* 0 0 1 1)

(define-objc-method ("unitRect" cocoa:ns-rect) ((self shapes))
  *unit-rect*)

(define-objc-method ("greeting:" objc-object-pointer) ((self shapes) (name objc-object-pointer string))
  (format nil "hello ~a" name))

(define-objc-method ("count:" count-type) ((self shapes) (items objc-object-pointer (array string)))
  (length items))

(define-objc-method ("selectorName:" objc-object-pointer) ((self shapes) (selector sel))
  (selector-name selector))

(define-objc-method ("className:" objc-object-pointer) ((self shapes) (class objc-class))
  (objc-class-name class))

(define-objc-method ("remember:" :void) ((self shapes) (object objc-object-pointer))
  (push (invoke-into 'string object "description") (shapes-log self)))

(define-objc-method ("many:b:c:d:e:f:g:h:i:j:" :double)
    ((self shapes) (a :int) (b :double) (c :int) (d :double) (e :int) (f :double) (g :int)
     (h :double) (i :int) (j :double))
  (+ a b c d e f g h i j))

(define-objc-method ("ints:b:c:d:e:f:g:h:" :long-long)
    ((self shapes) (a :int) (b :int) (c :short) (d (:signed :char)) (e :long-long) (f :int)
     (g :int) (h (:unsigned :short)))
  (+ a b c d e f g h))

(define-objc-method ("description" objc-object-pointer) ((self shapes))
  (concatenate 'string "shapes of " (invoke-into 'string (current-super) "className")))

(define-objc-class-method ("describeClass" objc-object-pointer) ((class shapes))
  (concatenate 'string "the class " (invoke-into 'string (current-super) "description")))

(define-objc-method ("fails:" :int) ((self shapes) (n :int))
  (error "no ~a" n))

(defvar *shapes* (make-instance 'shapes))
(show "double and float" (invoke *shapes* "sum:plus:" 1.5d0 2.25))
(show "long long" (invoke *shapes* "negate:" 12345678901))
(show "BOOL result" (list (invoke *shapes* "isBig:" 500) (invoke *shapes* "isBig:" 5)))
(show "BOOL argument" (list (invoke *shapes* "flag:" t) (invoke *shapes* "flag:" nil)))
(show "structure argument and result" (invoke *shapes* "scaled:by:" #(1 2 3 4) 2d0))
(show "result variable" (invoke *shapes* "rangeAfter:" '(3 . 4)))
(show "a structure of floats both ways" (invoke *shapes* "swapped:" #(1 2)))
(show "a scalar result variable" (invoke *shapes* "tally"))
(show "a foreign structure answered is copied" (invoke *shapes* "unitRect"))
(show "string style, string result" (invoke-into 'string *shapes* "greeting:" "world"))
(show "array style, typedef result" (invoke *shapes* "count:" #("a" "b" "c")))
(show "SEL argument" (invoke-into 'string *shapes* "selectorName:" "frame"))
(show "Class argument" (invoke-into 'string *shapes* "className:" "NSString"))
(invoke *shapes* "remember:" (invoke "NSNumber" "numberWithInt:" 5))
(show "object argument" (shapes-log *shapes*))
(show "arguments on the stack" (invoke *shapes* "many:b:c:d:e:f:g:h:i:j:" 1 2d0 3 4d0 5 6d0 7 8d0 9 10d0))
(show "integers on the stack" (invoke *shapes* "ints:b:c:d:e:f:g:h:" 1 2 3 -4 5 6 7 65535))
(show "super description" (invoke-into 'string *shapes* "description"))
(show "a class method, super" (invoke-into 'string "RontoLispCorpusShapes" "describeClass"))
(show "the encoding recorded"
      (third (multiple-value-list (objc-class-method-signature "RontoLispCorpusShapes" "sum:plus:"))))
(show "a failing method answers zero" (invoke *shapes* "fails:" 9))

;;; instance variables and an init method

(define-objc-class counter ()
  ()
  (:objc-class-name "RontoLispCorpusCounter")
  (:objc-instance-vars ("count" :int) ("label" objc-object-pointer) ("ratio" :double)))

(define-objc-method ("initWithCount:" objc-object-pointer) ((self counter) (n :int))
  (let ((object (invoke (current-super) "init")))
    (setf (objc-object-var-value object "count") n)
    object))

(define-objc-method ("increment" :int) ((self counter))
  (setf (objc-object-var-value self "count") (+ 1 (objc-object-var-value self "count"))))

(defvar *counter* (make-instance 'counter))
(show "an ivar starts zero" (objc-object-var-value *counter* "count"))
(setf (objc-object-var-value *counter* "count") 17)
(show "ivar round trip" (objc-object-var-value *counter* "count"))
(show "a method reads and writes it" (list (invoke *counter* "increment") (invoke *counter* "increment")))
(setf (objc-object-var-value *counter* "ratio") 0.25d0)
(show "double ivar" (objc-object-var-value *counter* "ratio"))
(setf (objc-object-var-value *counter* "label") (retain (string-to-ns-string "counted")))
(show "object ivar" (ns-string-to-string (objc-object-var-value *counter* "label")))
(show "unknown ivar" (refused (lambda () (objc-object-var-value *counter* "nope"))))
(show "an init method"
      (objc-object-var-value (invoke (invoke "RontoLispCorpusCounter" "alloc") "initWithCount:" 5)
                             "count"))

;;; an init-function, over a superclass Objective-C wrote

(define-objc-class boxed-view ()
  ()
  (:objc-class-name "RontoLispCorpusView")
  (:objc-superclass-name "NSView"))

(let ((view (make-instance 'boxed-view
                           :init-function (lambda (pointer &key frame &allow-other-keys)
                                            (invoke pointer "initWithFrame:" frame))
                           :frame #(0 0 30 40)
                           :allow-other-keys t)))
  (show "init-function" (invoke view "frame")))

;;; the lifecycle generics

(defvar *log* nil)

(define-objc-class tracked ()
  ((tag :initarg :tag :initform nil :accessor tracked-tag)
   (copied :initform nil :accessor tracked-copied))
  (:objc-class-name "RontoLispCorpusTracked"))

(defmethod objc-object-destroyed :after ((object tracked))
  (push (tracked-tag object) *log*))

(defmethod objc-object-copied :after ((old tracked) (new tracked))
  (setf (tracked-copied new) t))

(let* ((original (make-instance 'tracked :tag :original))
       (copy (objc-object-from-pointer (invoke original "copy"))))
  (show "a copy is its own Lisp object" (list (typep copy 'tracked) (eq copy original)))
  (show "objc-object-copied copied the slots" (tracked-tag copy))
  (show "the :after method ran" (tracked-copied copy))
  (setf (tracked-tag copy) :copy)
  (release copy)
  (show "destroyed when released" *log*)
  (release original)
  (show "destroyed in order" *log*))

;;; an observer

(define-objc-class observer ()
  ((seen :initform nil :accessor observer-seen))
  (:objc-class-name "RontoLispCorpusObserver"))

(define-objc-method ("noticed:" :void) ((self observer) (notification objc-object-pointer))
  (push (invoke-into 'string notification "name") (observer-seen self)))

(defvar *observer* (make-instance 'observer))
(defvar *center* (invoke "NSNotificationCenter" "defaultCenter"))
(cocoa:add-observer *observer* "noticed:" :name "RontoLispCorpusPing")
(invoke *center* "postNotificationName:object:" "RontoLispCorpusPing" nil)
(invoke *center* "postNotificationName:object:" "RontoLispCorpusOther" nil)
(show "observer" (observer-seen *observer*))
(cocoa:remove-observer *observer* :name "RontoLispCorpusPing")
(invoke *center* "postNotificationName:object:" "RontoLispCorpusPing" nil)
(show "removed observer" (length (observer-seen *observer*)))

;;; protocols

(define-objc-protocol "NSCopying"
  :instance-methods (("copyWithZone:" objc-object-pointer (:pointer :void))))

(define-objc-class conforming ()
  ()
  (:objc-class-name "RontoLispCorpusConforming")
  (:objc-protocols "NSCopying"))

(show "a protocol adopted" (objc-class-name (invoke (make-instance 'conforming) "class")))

;;; refusals

(show "a selector's arity"
      (refused (lambda ()
                 (objc::%define-objc-method 'shapes "a:b:" :void nil nil '((:int nil)) nil
                                            (lambda (&rest args) args)))))
(show "a class Lisp did not define"
      (refused (lambda () (objc::%realize (objc::%make-lisp-class 'x nil "NSObject" nil nil nil)))))
(show "a mixin has no Objective-C class" (refused (lambda () (make-instance 'my-size-mixin))))
