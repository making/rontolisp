;; The objc primitive layer on a --native output: the objc::% functions objc.lisp is
;; written over (see .kb/objc.md), here over the rlobjc p_* imports the runner answers
;; (rontolisp-native/runner/src/objc/prim.rs). The interpreter's twin is
;; eval/ObjcPrimitives.java and a JVM class's is
;; codegen/jvm/JvmObjcPrimitivesTemplate.java; none of them decides a rule.
;;
;; Also objc:on-main, a plain call because the module runs on thread 0, and
;; objc::%sleep, which every sleep of such a program compiles to -- thread 0's event
;; loop for that long.
;;
;; No interning: a wasm-GC module has no weak reference, and a table keeping every
;; pointer would keep every reference. The backend compares an objc-object-pointer by
;; its address slot instead, so two answers for one object are still eq.

(rontolisp:wasm-import 'objc::%p-error
                       :from "rlobjc"
                       :as "p_error"
                       :returns :string)
(rontolisp:wasm-import 'objc::%get-class
                       :from "rlobjc"
                       :as "p_get_class"
                       :params '(:string)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%class-name
                       :from "rlobjc"
                       :as "p_class_name"
                       :params '(:s64)
                       :returns :string)
(rontolisp:wasm-import 'objc::%object-class
                       :from "rlobjc"
                       :as "p_object_class"
                       :params '(:s64)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-class-p
                       :from "rlobjc"
                       :as "p_class_p"
                       :params '(:s64)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%register-selector
                       :from "rlobjc"
                       :as "p_register_selector"
                       :params '(:string)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%selector-name
                       :from "rlobjc"
                       :as "p_selector_name"
                       :params '(:s64)
                       :returns :string)
(rontolisp:wasm-import 'objc::%p-method-types
                       :from "rlobjc"
                       :as "p_method_types"
                       :params '(:s64 :s64)
                       :returns :string)
(rontolisp:wasm-import 'objc::%p-arg-int
                       :from "rlobjc"
                       :as "p_arg_int"
                       :params '(:s64))
(rontolisp:wasm-import 'objc::%p-arg-float
                       :from "rlobjc"
                       :as "p_arg_float"
                       :params '(:float))
(rontolisp:wasm-import 'objc::%p-arg-string
                       :from "rlobjc"
                       :as "p_arg_string"
                       :params '(:string))
(rontolisp:wasm-import 'objc::%p-arg-leaves
                       :from "rlobjc"
                       :as "p_arg_leaves"
                       :params '(:s32))
(rontolisp:wasm-import 'objc::%p-send
                       :from "rlobjc"
                       :as "p_send"
                       :params '(:s64 :s64 :string :s32 :s32)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-result-int
                       :from "rlobjc"
                       :as "p_result_int"
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-result-float
                       :from "rlobjc"
                       :as "p_result_float"
                       :returns :float)
(rontolisp:wasm-import 'objc::%p-result-string
                       :from "rlobjc"
                       :as "p_result_string"
                       :returns :string)
(rontolisp:wasm-import 'objc::%p-result-count
                       :from "rlobjc"
                       :as "p_result_count"
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-result-leaf-float-p
                       :from "rlobjc"
                       :as "p_result_leaf_float_p"
                       :params '(:s32)
                       :returns :bool)
(rontolisp:wasm-import 'objc::%p-result-leaf-int
                       :from "rlobjc"
                       :as "p_result_leaf_int"
                       :params '(:s32)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-result-leaf-float
                       :from "rlobjc"
                       :as "p_result_leaf_float"
                       :params '(:s32)
                       :returns :float)
(rontolisp:wasm-import 'objc::%new-handle
                       :from "rlobjc"
                       :as "p_new_handle"
                       :params '(:s64 :s64)
                       :returns :extern)
(rontolisp:wasm-import 'objc::%refs
                       :from "rlobjc"
                       :as "p_refs"
                       :params '(:extern :s32 :s64)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-load-module
                       :from "rlobjc"
                       :as "p_load_module"
                       :params '(:string)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-initialize
                       :from "rlobjc"
                       :as "p_initialize"
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-pump
                       :from "rlobjc"
                       :as "p_pump"
                       :params '(:float))

;; The class-definition half (objc-class.lisp is written over these too; the host side
;; is rontolisp-native/runner/src/objc/class.rs).
(rontolisp:wasm-import 'objc::%allocate-class
                       :from "rlobjc"
                       :as "p_allocate_class"
                       :params '(:s64 :string)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-add-ivar
                       :from "rlobjc"
                       :as "p_add_ivar"
                       :params '(:s64 :string :s64 :s64 :string)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-register-class
                       :from "rlobjc"
                       :as "p_register_class"
                       :params '(:s64))
(rontolisp:wasm-import 'objc::%p-add-method
                       :from "rlobjc"
                       :as "p_add_method"
                       :params '(:s64 :s64 :string :s32 :s32)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-add-protocol
                       :from "rlobjc"
                       :as "p_add_protocol"
                       :params '(:s64 :string)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%superclass
                       :from "rlobjc"
                       :as "p_superclass"
                       :params '(:s64)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-send-super
                       :from "rlobjc"
                       :as "p_send_super"
                       :params '(:s64 :s64 :s64 :string :s32 :s32)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%ivar-offset
                       :from "rlobjc"
                       :as "p_ivar_offset"
                       :params '(:s64 :string)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-ivar-types
                       :from "rlobjc"
                       :as "p_ivar_types"
                       :params '(:s64 :string)
                       :returns :string)
(rontolisp:wasm-import 'objc::%p-peek
                       :from "rlobjc"
                       :as "p_peek"
                       :params '(:s64 :string)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-poke
                       :from "rlobjc"
                       :as "p_poke"
                       :params '(:s64 :string)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-cb-count
                       :from "rlobjc"
                       :as "p_cb_count"
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-cb-arg
                       :from "rlobjc"
                       :as "p_cb_arg"
                       :params '(:s32)
                       :returns :s32)

;; The blocks and C functions half (objc-block.lisp and fli:define-foreign-function are
;; written over these; the host side is rontolisp-native/runner/src/objc/block.rs).
(rontolisp:wasm-import 'objc::%p-make-block
                       :from "rlobjc"
                       :as "p_make_block"
                       :params '(:string :string :s32)
                       :returns :s64)
(rontolisp:wasm-import 'objc::%p-free-block
                       :from "rlobjc"
                       :as "p_free_block"
                       :params '(:s64))
(rontolisp:wasm-import 'objc::%p-block-reap
                       :from "rlobjc"
                       :as "p_block_reap"
                       :returns :s32)
(rontolisp:wasm-import 'objc::%p-call-function
                       :from "rlobjc"
                       :as "p_call_function"
                       :params '(:s64 :string :s32 :s32)
                       :returns :s32)
(rontolisp:wasm-import 'objc::%symbol-address
                       :from "rlobjc"
                       :as "p_symbol"
                       :params '(:string)
                       :returns :s64)

;; The byte copies objc:data and objc:bytes are written over.
(rontolisp:wasm-import 'objc::%p-write-bytes
                       :from "rlobjc"
                       :as "p_write_bytes"
                       :params '(:s64 :bytes))
(rontolisp:wasm-import 'objc::%p-read-bytes
                       :from "rlobjc"
                       :as "p_read_bytes"
                       :params '(:s64)
                       :returns :bytes)

(defun objc::%class-p (address) (/= (objc::%p-class-p address) 0))

(defun objc::%method-types (cls sel)
  (let ((types (objc::%p-method-types cls sel)))
    (if (= (length types) 0) nil types)))

(defun objc::%interned (address)
  (declare (ignore address))
  nil)

(defun objc::%intern (address pointer)
  (declare (ignore address))
  pointer)

(defun objc::%load-module (path)
  (when (= (objc::%p-load-module path) 0) (error "objc: ~a" (objc::%p-error)))
  t)

(defun objc::%initialize ()
  (when (= (objc::%p-initialize) 0) (error "objc: ~a" (objc::%p-error)))
  t)

(defun objc::%p-push (value)
  (cond ((integerp value) (objc::%p-arg-int value))
        ((floatp value) (objc::%p-arg-float value))
        ((stringp value) (objc::%p-arg-string value))
        ((listp value)
         (objc::%p-arg-leaves (length value))
         (dolist (leaf value)
           (if (integerp leaf)
               (objc::%p-arg-int leaf)
               (objc::%p-arg-float leaf))))
        (t (error "objc: cannot pass ~s to Objective-C" value))))

(defun objc::%p-leaves ()
  (let ((leaves nil))
    (dotimes (i (objc::%p-result-count))
      (push (if (objc::%p-result-leaf-float-p i)
                (objc::%p-result-leaf-float i)
                (objc::%p-result-leaf-int i)) leaves))
    (nreverse leaves)))

(defun objc::%send (receiver sel types fixed args mode)
  (dolist (arg args) (objc::%p-push arg))
  (objc::%p-answer (objc::%p-send receiver sel types fixed mode)))

(defun objc::%send-super (receiver class sel types fixed args mode)
  (dolist (arg args) (objc::%p-push arg))
  (objc::%p-answer (objc::%p-send-super receiver class sel types fixed mode)))

;; What the last call raised, retained (0 for nil), until objc::%raised reads it.
(defvar objc::*raised* nil)

(defun objc::%raised ()
  (let ((thrown objc::*raised*))
    (setq objc::*raised* nil)
    thrown))

;; The value of the last answer the host made, by its kind. A call that raised answers
;; nil and leaves what it threw for objc::%raised.
(defun objc::%p-answer (kind)
  (case kind
    (0
     (setq objc::*raised* nil)
     nil)
    (1 (objc::%p-result-int))
    (2 (objc::%p-result-float))
    (3 (objc::%p-result-string))
    (4 (objc::%p-leaves))
    (5
     (setq objc::*raised* (objc::%p-result-int))
     nil)
    (t (error "objc: ~a" (objc::%p-error)))))

(defun objc::%add-ivar (cls name size alignment types)
  (/= (objc::%p-add-ivar cls name size alignment types) 0))

(defun objc::%register-class (cls)
  (objc::%p-register-class cls)
  t)

(defun objc::%add-protocol (cls name) (/= (objc::%p-add-protocol cls name) 0))

(defun objc::%ivar-types (cls name)
  (let ((types (objc::%p-ivar-types cls name)))
    (if (= (length types) 0) nil types)))

(defun objc::%peek (address types)
  (objc::%p-answer (objc::%p-peek address types)))

(defun objc::%poke (address types raw)
  (objc::%p-push raw)
  (objc::%p-answer (objc::%p-poke address types))
  nil)

;; Method index -> the function a defined method runs: (lambda (self raw-args) raw).
(defvar objc::*methods* (make-hash-table))

(defvar objc::*method-count* 0)

(defun objc::%add-method (cls sel types function flags)
  (let ((index objc::*method-count*))
    (setf (gethash index objc::*methods*) function)
    (setq objc::*method-count* (+ index 1))
    (when (= (objc::%p-add-method cls sel types index flags) 0)
      (error "objc: ~a" (objc::%p-error)))
    t))

(defun objc::%p-method (index self)
  (let ((args nil))
    (dotimes (i (objc::%p-cb-count))
      (push (objc::%p-answer (objc::%p-cb-arg i)) args))
    (let ((answer
           (funcall (gethash index objc::*methods*) self (nreverse args))))
      (when answer (objc::%p-push answer)))
    1))

;; A block's function joins the methods' table: the host calls it through the same
;; export, with no receiver. The host reports the blocks no copy holds any more.
(defun objc::%reap-blocks ()
  (do ((index (objc::%p-block-reap) (objc::%p-block-reap)))
      ((< index 0))
    (remhash index objc::*methods*)))

(defun objc::%make-block (types signature function)
  (objc::%reap-blocks)
  (let ((index objc::*method-count*))
    (setf (gethash index objc::*methods*)
          (lambda (self args)
            (declare (ignore self))
            (funcall function args)))
    (setq objc::*method-count* (+ index 1))
    (let ((address (objc::%p-make-block types signature index)))
      (when (= address 0)
        (remhash index objc::*methods*)
        (error "objc: ~a" (objc::%p-error)))
      address)))

(defun objc::%free-block (address)
  (objc::%p-free-block address)
  (objc::%reap-blocks)
  nil)

(defun objc::%call-function (function types fixed args mode)
  (dolist (arg args) (objc::%p-push arg))
  (objc::%p-answer (objc::%p-call-function function types fixed mode)))

;; The bytes objc:data sends: what write-sequence writes for a packed buffer --
;; little-endian, row-major, the elements only (eval/PackedBuffer) -- or a string's
;; UTF-8; nil for any other value. Laid out here: a wasm-GC array is no block of memory
;; the host could read.
(defun objc::%little-endian (array width bits-of)
  (let* ((n (array-total-size array))
         (out (make-array (* n width) :element-type '(unsigned-byte 8)))
         (k 0))
    (dotimes (i n out)
      (let ((bits (funcall bits-of (row-major-aref array i))))
        (dotimes (b width)
          (setf (aref out k) (ldb (byte 8 (* 8 b)) bits))
          (setq k (+ k 1)))))))

(defun objc::%octets (value)
  (cond ((stringp value) (rontolisp:string-to-octets value))
        ((typep value '(vector (unsigned-byte 8))) (copy-seq value))
        ((typep value '(array single-float))
         (objc::%little-endian value 4 (lambda (x) (%ieee754-single-bits x))))
        ((typep value '(array double-float))
         (objc::%little-endian value 8 (lambda (x) (%ieee754-double-bits x))))
        ((typep value '(vector (unsigned-byte 16)))
         (objc::%little-endian value 2 (lambda (x) x)))
        ((typep value '(vector (unsigned-byte 32)))
         (objc::%little-endian value 4 (lambda (x) x)))
        (t nil)))

(defun objc::%write-octets (address octets)
  (objc::%p-write-bytes address octets)
  nil)

(defun objc::%read-octets (address length)
  (let ((buffer (make-array length :element-type '(unsigned-byte 8))))
    (objc::%p-read-bytes address buffer)
    buffer))

(rontolisp:wasm-export 'objc::%p-method
                       :as "rlobjc_method"
                       :params '(:s32 :s64)
                       :returns :s32)

(defun objc:on-main (function) (funcall function))

;; What sleep compiles to: thread 0's event loop for that long, so a window stays
;; alive while the program waits.
(defun objc::%sleep (seconds)
  (let ((s (* 1.0 seconds))) (when (> s 0) (objc::%p-pump s)))
  nil)
