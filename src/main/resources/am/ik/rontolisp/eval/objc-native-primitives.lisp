;; The new objc base's primitive layer on a --native output: the objc::% functions
;; objc.lisp is written over (see .kb/objc.md, "The new base"), here over the rlobjc
;; p_* imports the runner answers (rontolisp-native/runner/src/objc/prim.rs). The
;; interpreter's twin is eval/ObjcPrimitives.java and a JVM class's is
;; codegen/jvm/JvmObjcPrimitivesTemplate.java; none of them decides a rule.
;;
;; Also what both bases share on this target: objc:on-main, a plain call because the
;; module runs on thread 0, and objc::%sleep, which every sleep of such a program
;; compiles to -- thread 0's event loop for that long.
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
  (let ((kind (objc::%p-send receiver sel types fixed mode)))
    (case kind
      (0 nil)
      (1 (objc::%p-result-int))
      (2 (objc::%p-result-float))
      (3 (objc::%p-result-string))
      (4 (objc::%p-leaves))
      (t (error "objc: ~a" (objc::%p-error))))))

(defun objc:on-main (function) (funcall function))

;; What sleep compiles to: thread 0's event loop for that long, so a window stays
;; alive while the program waits.
(defun objc::%sleep (seconds)
  (let ((s (* 1.0 seconds))) (when (> s 0) (objc::%p-pump s)))
  nil)
