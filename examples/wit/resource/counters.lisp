;;;; counters -- a world that exports a resource.
;;;;
;;;; It implements the world `counters` of wit/counters.wit. The interface
;;;; `registry` declares the resource `counter`; a program implements one with
;;;; ordinary functions, named as an import of the interface binds them:
;;;;
;;;;   constructor(...)      counter-new      answers the counter: ANY Lisp value
;;;;   add: func(...)        counter-add      a method takes that value first
;;;;   merge: static func    counter-merge
;;;;   (a handle released)   counter-drop     optional, called with the value
;;;;
;;;; The value the constructor answers IS the resource: here a struct. The host
;;;; holds a handle of it, and every function that takes or answers a `counter`
;;;; gets or gives the struct -- alone, in a list, in a result, anywhere.
;;;;
;;;;   rontolisp counters.lisp -o counters.wasm --component
;;;;
;;;; app.lisp calls the two interfaces from another component -- see README.md.

(defstruct counter name value)

;;; The counters made and not yet released, for `live`.
(defvar *live* 0)

;;; WIT: constructor(name: string, start: u32)
(defun counter-new (name start)
  (incf *live*)
  (make-counter :name name :value start))

;;; WIT: add: func(by: u32) -> u32
(defun counter-add (self by) (incf (counter-value self) by))

;;; WIT: read: func() -> reading
(defun counter-read (self)
  (list :name (counter-name self) :value (counter-value self)))

;;; WIT: merge: static func(a: counter, b: borrow<counter>) -> counter
;;; `a` arrives owned: the caller's handle of it is gone, and the value is this
;;; function's to keep, change or hand back. `b` is only lent.
(defun counter-merge (a b)
  (setf (counter-name a)
        (concatenate 'string (counter-name a) "+" (counter-name b)))
  (incf (counter-value a) (counter-value b))
  a)

;;; Called with the value when a handle of it is released. A counter handed over
;;; to an export (merge's `a`, leader's list) is not released: it moved here.
(defun counter-drop (self)
  (declare (ignore self))
  (decf *live*))

;;; WIT: total: func(counters: list<borrow<counter>>) -> u32
(defun total (counters) (reduce #'+ counters :key #'counter-value))

;;; WIT: leader: func(counters: list<counter>) -> result<counter, string>
;;; The counters arrive owned. The leader goes back to the caller; the others
;;; stay here with no handle left, so they are closed by hand.
(defun leader (counters)
  (when (null counters)
    (error 'rontolisp:wit-error :payload "no counter to lead"))
  (let ((best (first counters)))
    (dolist (c (rest counters))
      (when (> (counter-value c) (counter-value best)) (setq best c)))
    (dolist (c counters) (unless (eq c best) (decf *live*)))
    best))

;;; WIT: live: func() -> u32
(defun live () *live*)

;;; WIT (interface report): summary: func(c: borrow<counter>) -> string
(defun summary (c)
  (format nil "~a is at ~d" (counter-name c) (counter-value c)))

(rontolisp:wit-export "wit/counters.wit" :reactor t)
