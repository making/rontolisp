;; The objc: verbs of a --native output: the same nine the interpreter binds through
;; FFM (ObjcBridge.java) and a compiled class carries (JvmObjcTemplate.java), written
;; here in rontolisp over the runner's Objective-C host -- the rlobjc imports, which
;; rontolisp-native/runner/src/objc answers (see ObjcNativeLibrary.java and
;; .kb/objc.md, "--native"). Spliced by the compile path into a --native program that
;; references objc:, appkit:, metal: or scene:; never into a .wasm, which no host
;; answers.
;;
;; Threads: the module runs ON thread 0, so there is no hop -- objc:on-main is a plain
;; call, and a callback (a button's action, a timer) arrives inside a send or inside
;; sleep, which the host turns into thread 0's event loop (objc::%sleep, which the
;; backend compiles every sleep to in such a program).
;;
;; Ownership: an object a send answers arrives retained (+1), exactly as the JVM
;; wrapper takes it; nothing here releases it yet (the safe direction), since a wasm-GC
;; module has no finalizer to release it with.
;;
;; Portability constraints honored here (like appkit.lisp): do loops always declare at
;; least one variable; parameters are never assigned with setq.

(rontolisp:wasm-import 'objc::%rl-available :from "rlobjc" :as "available" :returns :bool)
(rontolisp:wasm-import 'objc::%rl-error :from "rlobjc" :as "error" :returns :string)
(rontolisp:wasm-import 'objc::%rl-class :from "rlobjc" :as "class" :params '(:string) :returns :s64)
(rontolisp:wasm-import 'objc::%rl-class-name :from "rlobjc" :as "class_name" :params '(:s64)
 :returns :string)
(rontolisp:wasm-import 'objc::%rl-arg-nil :from "rlobjc" :as "arg_nil")
(rontolisp:wasm-import 'objc::%rl-arg-true :from "rlobjc" :as "arg_true")
(rontolisp:wasm-import 'objc::%rl-arg-error :from "rlobjc" :as "arg_error")
(rontolisp:wasm-import 'objc::%rl-arg-int :from "rlobjc" :as "arg_int" :params '(:s64))
(rontolisp:wasm-import 'objc::%rl-arg-float :from "rlobjc" :as "arg_float" :params '(:float))
(rontolisp:wasm-import 'objc::%rl-arg-object :from "rlobjc" :as "arg_object" :params '(:s64))
(rontolisp:wasm-import 'objc::%rl-arg-string :from "rlobjc" :as "arg_string" :params '(:string))
(rontolisp:wasm-import 'objc::%rl-arg-struct :from "rlobjc" :as "arg_struct" :params '(:s32))
(rontolisp:wasm-import 'objc::%rl-send :from "rlobjc" :as "send" :params '(:s64 :string) :returns :s32)
(rontolisp:wasm-import 'objc::%rl-result-int :from "rlobjc" :as "result_int" :returns :s64)
(rontolisp:wasm-import 'objc::%rl-result-float :from "rlobjc" :as "result_float" :returns :float)
(rontolisp:wasm-import 'objc::%rl-result-string :from "rlobjc" :as "result_string" :returns :string)
(rontolisp:wasm-import 'objc::%rl-result-count :from "rlobjc" :as "result_count" :returns :s32)
(rontolisp:wasm-import 'objc::%rl-result-leaf-float-p :from "rlobjc" :as "result_leaf_float_p"
 :params '(:s32) :returns :bool)
(rontolisp:wasm-import 'objc::%rl-result-leaf-int :from "rlobjc" :as "result_leaf_int" :params '(:s32)
 :returns :s64)
(rontolisp:wasm-import 'objc::%rl-result-leaf-float :from "rlobjc" :as "result_leaf_float"
 :params '(:s32) :returns :float)
(rontolisp:wasm-import 'objc::%rl-define-class :from "rlobjc" :as "define_class"
 :params '(:string :string :string :string :s32) :returns :s64)
(rontolisp:wasm-import 'objc::%rl-string :from "rlobjc" :as "string" :params '(:string) :returns :s64)
(rontolisp:wasm-import 'objc::%rl-data :from "rlobjc" :as "data" :params '(:bytes) :returns :s64)
(rontolisp:wasm-import 'objc::%rl-data-length :from "rlobjc" :as "data_length" :params '(:s64)
 :returns :s64)
(rontolisp:wasm-import 'objc::%rl-data-bytes :from "rlobjc" :as "data_bytes" :params '(:s64)
 :returns :bytes)
(rontolisp:wasm-import 'objc::%rl-pump :from "rlobjc" :as "pump" :params '(:float))

;; An object or a class: its address. A class owns nothing; an object owns one
;; reference, released never (see the header).
(defstruct (objc::%object (:constructor objc::%make-object (address))
            (:predicate objc::%objectp))
  address)

(defmethod print-object ((object objc::%object) stream)
  (format stream "#<objc ~a>" (objc::%rl-class-name (objc::%object-address object))))

(defun objc::%fail (verb)
  (error "objc:~a: ~a" verb (objc::%rl-error)))

(defun objc::%class-address (name)
  (let ((address (objc::%rl-class name)))
    (when (= address 0) (objc::%fail "send"))
    address))

(defun objc:class (name)
  (unless (stringp name)
    (error "objc:class expects a string, got ~s" name))
  (let ((address (objc::%rl-class name)))
    (when (= address 0) (objc::%fail "class"))
    (objc::%make-object address)))

(defun objc:objectp (value) (if (objc::%objectp value) t nil))

(defun objc:address (object)
  (unless (objc::%objectp object)
    (error "objc:address expects an Objective-C object, got ~s" object))
  (objc::%object-address object))

;; Checks one argument BEFORE any is handed over, so a refused one leaves nothing
;; half-pushed for the next send.
(defun objc::%check-arg (value)
  (cond ((or (null value) (eq value t) (eq value :error) (objc::%objectp value)
             (stringp value) (integerp value) (floatp value))
         nil)
        ((consp value)
         (dolist (leaf value)
           (unless (or (integerp leaf) (floatp leaf))
             (error "objc:send: a struct is a list of numbers, got ~s" value))))
        (t (error "objc:send: cannot pass ~s to Objective-C" value))))

(defun objc::%push-leaf (leaf)
  (if (integerp leaf) (objc::%rl-arg-int leaf) (objc::%rl-arg-float leaf)))

(defun objc::%push-arg (value)
  (cond ((null value) (objc::%rl-arg-nil))
        ((eq value t) (objc::%rl-arg-true))
        ((eq value :error) (objc::%rl-arg-error))
        ((objc::%objectp value) (objc::%rl-arg-object (objc::%object-address value)))
        ((stringp value) (objc::%rl-arg-string value))
        ((integerp value) (objc::%rl-arg-int value))
        ((floatp value) (objc::%rl-arg-float value))
        (t
         (objc::%rl-arg-struct (length value))
         (dolist (leaf value) (objc::%push-leaf leaf)))))

(defun objc::%struct-answer ()
  (let ((leaves nil))
    (dotimes (i (objc::%rl-result-count))
      (push (if (objc::%rl-result-leaf-float-p i)
                (objc::%rl-result-leaf-float i)
                (objc::%rl-result-leaf-int i))
            leaves))
    (nreverse leaves)))

;; The kinds the host's send answers (runner/src/objc, mod answer).
(defun objc::%answer (kind)
  (case kind
    (0 nil)
    (1 (objc::%make-object (objc::%rl-result-int)))
    (2 (objc::%make-object (objc::%rl-result-int)))
    (3 (objc::%rl-result-int))
    (4 (objc::%rl-result-string))
    (5 t)
    (6 (objc::%rl-result-float))
    (7 (objc::%struct-answer))
    (t (objc::%fail "send"))))

(defun objc:send (receiver selector &rest args)
  (unless (stringp selector)
    (error "objc:send expects (objc:send receiver \"selector\" args...)"))
  (if (null receiver)
      nil
      (let ((address
             (cond ((objc::%objectp receiver) (objc::%object-address receiver))
                   ((stringp receiver) (objc::%class-address receiver))
                   (t
                    (error "objc:send: the receiver must be an object or a class name, got ~s"
                           receiver)))))
        (dolist (arg args) (objc::%check-arg arg))
        (dolist (arg args) (objc::%push-arg arg))
        (objc::%answer (objc::%rl-send address selector)))))

(defun objc:string (text)
  (unless (stringp text)
    (error "objc:string expects a string, got ~s" text))
  (let ((address (objc::%rl-string text)))
    (when (= address 0) (objc::%fail "string"))
    (objc::%make-object address)))

(defun objc:data (value)
  (let ((bytes
         (cond ((stringp value) (rontolisp:string-to-octets value))
               ((typep value '(vector (unsigned-byte 8))) value)
               (t
                (error "objc:data expects a packed float array, a packed (unsigned-byte 8|16|32) vector or a string, got ~s"
                       value)))))
    (let ((address (objc::%rl-data bytes)))
      (when (= address 0) (objc::%fail "data"))
      (objc::%make-object address))))

(defun objc:bytes (data)
  (unless (objc::%objectp data)
    (error "objc:bytes expects an Objective-C object, got ~s" data))
  (let ((n (objc::%rl-data-length (objc::%object-address data))))
    (when (< n 0) (objc::%fail "bytes"))
    (let ((buffer (make-array n :element-type '(unsigned-byte 8))))
      (when (< (objc::%rl-data-bytes (objc::%object-address data) buffer) 0)
        (objc::%fail "bytes"))
      buffer)))

(defun objc:on-main (function) (funcall function))

;;; --- classes whose methods are Lisp closures --------------------------------

;; Closure id -> the function a defined method calls.
(defvar objc::*callbacks* (make-hash-table))

(defvar objc::*next-callback* 0)

(defun objc::%lines (items what)
  (let ((text ""))
    (dolist (item items)
      (unless (stringp item) (error "objc:define-class: ~a is a string, got ~s" what item))
      (setq text (concatenate 'string text item (string #\Newline))))
    text))

(defun objc:define-class (name superclass methods &optional protocols)
  (unless (and (stringp name) (stringp superclass))
    (error "objc:define-class expects (objc:define-class \"Name\" \"Superclass\" methods [protocols])"))
  (let ((first objc::*next-callback*)
        (selectors nil))
    (dolist (method methods)
      (unless (and (consp method) (consp (cdr method)) (null (cddr method)) (stringp (car method)))
        (error "objc:define-class: a method is (\"selector:\" function), got ~s" method))
      (setf (gethash objc::*next-callback* objc::*callbacks*) (cadr method))
      (setq objc::*next-callback* (+ objc::*next-callback* 1))
      (push (car method) selectors))
    (let ((address
           (objc::%rl-define-class name superclass (objc::%lines protocols "a protocol")
                                   (objc::%lines (reverse selectors) "a selector") first)))
      (when (= address 0) (objc::%fail "define-class"))
      (objc::%make-object address))))

;; A method's answer as the IMP hands it back: an object's address, a boolean as 1/0,
;; an integer as itself.
(defun objc::%callback-result (value)
  (cond ((objc::%objectp value) (objc::%object-address value))
        ((integerp value) value)
        ((null value) 0)
        (t 1)))

;; The upcall every defined method makes (the host's IMP): the receiver and up to two
;; object arguments, each retained for its wrapper. An error the method does not
;; handle is printed and answered as the zero value -- it must never unwind into the
;; native frame above.
(defun objc::%callback (id self first second argc)
  (let ((function (gethash id objc::*callbacks*))
        (args
         (cond ((= argc 0) nil)
               ((= argc 1) (list (objc::%wrap first)))
               (t (list (objc::%wrap first) (objc::%wrap second))))))
    (handler-case
        (objc::%callback-result (apply function (objc::%make-object self) args))
      (error (condition)
        (format *error-output* "objc: error in a callback: ~a~%" condition)
        0))))

(defun objc::%wrap (address) (if (= address 0) nil (objc::%make-object address)))

(rontolisp:wasm-export 'objc::%callback :as "rlobjc_callback" :params '(:s32 :s64 :s64 :s64 :s32)
 :returns :s64)

;; What sleep compiles to: thread 0's event loop for that long, so a window stays
;; alive while the program waits.
(defun objc::%sleep (seconds)
  (let ((s (* 1.0 seconds)))
    (when (> s 0) (objc::%rl-pump s)))
  nil)
