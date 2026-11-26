;;;; wit-resource.lisp -- the values behind the handles of an exported resource.
;;;;
;;;; A world whose exported interface declares a `resource` is implemented with
;;;; ordinary Lisp values: what the constructor answers IS the resource, and a
;;;; method receives that value. A component's handle carries one i32, its
;;;; representation, so the wrappers of the exports keep each value in a slot
;;;; here and hand the host the slot's index:
;;;;
;;;;   an owned handle answered   %wit-rep-new, then canon resource.new
;;;;   a borrowed handle received %wit-rep-value of the representation it is
;;;;   an owned handle received   canon resource.rep, %wit-rep-take, then canon
;;;;                              resource.drop, whose destructor call finds
;;;;                              the slot taken and only frees it
;;;;   the host releases a handle the destructor: %wit-rep-free, then the
;;;;                              program's <resource>-drop on what it held
;;;;
;;;; Spliced by WitExportInliner into a program whose world exports a resource,
;;;; on a component only: the interpreter and the JVM pass the value itself.
;;;;
;;;; Nothing here is initialized by a top-level form: a component that is no
;;;; reactor runs its top level in `run`, which a host calling an export never
;;;; calls. Both variables start as nil either way.

;;; Slot i holds the value of representation i; a free slot holds the index of
;;; the next free one, the last nil. The vector is made by the first handle.
(defvar rontolisp::*wit-reps* nil)

;;; The first free slot, or nil when every slot holds a value.
(defvar rontolisp::*wit-rep-free* nil)

(defun rontolisp::%wit-rep-new (value)
  (let ((rep rontolisp::*wit-rep-free*))
    (if rep
        (setq rontolisp::*wit-rep-free* (svref rontolisp::*wit-reps* rep))
        ;; every slot is in use: double the store, take its first new slot and
        ;; thread the others as the free list
        (let* ((reps rontolisp::*wit-reps*)
               (size (if reps (length reps) 0))
               (grown (make-array (if reps (+ size size) 8) :initial-element nil)))
          (dotimes (i size) (setf (svref grown i) (svref reps i)))
          (do ((i (+ size 1) (+ i 1)))
              ((>= i (- (length grown) 1)))
            (setf (svref grown i) (+ i 1)))
          (setq rontolisp::*wit-reps* grown)
          (setq rontolisp::*wit-rep-free* (+ size 1))
          (setq rep size)))
    (setf (svref rontolisp::*wit-reps* rep) value)
    rep))

(defun rontolisp::%wit-rep-value (rep) (svref rontolisp::*wit-reps* rep))

;;; The value of an owned handle a host passed in. The slot holds a marker
;;; until the handle is released, so that the destructor leaves the value alone.
(defun rontolisp::%wit-rep-take (rep)
  (let ((value (svref rontolisp::*wit-reps* rep)))
    (setf (svref rontolisp::*wit-reps* rep) 'rontolisp::%wit-rep-taken)
    value))

(defun rontolisp::%wit-rep-free (rep)
  (let ((value (svref rontolisp::*wit-reps* rep)))
    (setf (svref rontolisp::*wit-reps* rep) rontolisp::*wit-rep-free*)
    (setq rontolisp::*wit-rep-free* rep)
    value))
