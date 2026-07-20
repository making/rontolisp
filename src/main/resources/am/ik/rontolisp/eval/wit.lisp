;;;; wit.lisp -- the runtime half of rontolisp:wit-import.
;;;;
;;;; A (rontolisp:wit-import "kv.wit" :interface "..." :package kv) directive
;;;; lowers, on the interpreter and the JVM backend, into one ordinary defun
;;;; per WIT function whose body is
;;;;
;;;;   (rontolisp::%wit-call "<interface>" "<member>" arg...)
;;;;
;;;; This file is what that call lands in: a registry of providers keyed by WIT
;;;; interface id, and the condition a WIT result's error arm signals. It is
;;;; written in rontolisp itself (the usocket.lisp / linalg.lisp pattern) so a
;;;; single implementation serves both backends -- the WASM backends never see
;;;; it, because there a wit-import lowers to rontolisp:wasm-import and the host
;;;; supplies the functions.
;;;;
;;;; A provider is an ordinary Lisp callable taking the bound function's name
;;;; (a string: the Lisp member name, e.g. "open" or "bucket-get") followed by
;;;; that function's arguments. The error arm of a WIT result<T, E> is expressed
;;;; by signaling rontolisp:wit-error with the mapped E payload -- the settled
;;;; type mapping (.kb/wit.md): the ok arm is the value, the error arm is a
;;;; condition, on every backend.
;;;;
;;;; This file deliberately ships NO provider for any concrete interface. The
;;;; core knows about the provider MECHANISM; it does not know what
;;;; wasi:keyvalue is. An implementation of a WIT interface is ordinary user
;;;; code -- see examples/wit/keyvalue for an in-memory one, which is a plain
;;;; Lisp file a program loads.

(defvar rontolisp::*wit-providers* (make-hash-table :test #'equal))

(define-condition rontolisp:wit-error (error)
  ((payload :initarg :payload :initform nil
            :reader rontolisp:wit-error-payload)
   (message :initarg :message :initform "WIT call failed"
            :reader rontolisp::%wit-error-message))
  (:report (lambda (c s) (write-string (rontolisp::%wit-error-message c) s))))

(defun rontolisp:wit-provide (interface provider)
  ;; Bind the implementation of a wit-imported interface. The provider replaces
  ;; any previous one, so a program can swap an in-memory store for a real one
  ;; without touching the code that calls the interface.
  (setf (gethash interface rontolisp::*wit-providers*) provider)
  interface)

(defun rontolisp::%wit-call (interface member &rest args)
  (let ((provider (gethash interface rontolisp::*wit-providers*)))
    (if (null provider)
        (error 'rontolisp:wit-error :payload interface :message
               (concatenate 'string "No provider is bound for the WIT interface "
                            interface " -- bind one with rontolisp:wit-provide"))
        (apply provider member args))))

(defun rontolisp::%wit-result (envelope)
  ;; The WASM boundaries cannot signal across the host call, so a
  ;; result-returning wit-imported function crosses as the envelope
  ;; (:ok . value) / (:error . payload) and its public wrapper defun unwraps it
  ;; here: the ok arm IS the value, the error arm signals rontolisp:wit-error
  ;; exactly as an interpreter/JVM provider would have (the settled result
  ;; mapping, a condition on every backend).
  (if (and (consp envelope) (or (eq (car envelope) :ok) (eq (car envelope) :OK)))
      (cdr envelope)
      (if (and (consp envelope) (or (eq (car envelope) :error) (eq (car envelope) :ERROR)))
          (error 'rontolisp:wit-error :payload (cdr envelope)
                 :message (concatenate 'string "the WIT call answered its error arm: "
                                       (prin1-to-string (cdr envelope))))
          envelope)))
