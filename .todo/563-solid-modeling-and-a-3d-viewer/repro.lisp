;;;; repro.lisp -- the hash-table defect this spike ran into, minimized.
;;;;
;;;; Two facts, one root cause: a table places its keys by a STRUCTURAL hash
;;;; whose recursion is bounded by DEPTH (64 levels, .kb/hash-tables.md) but not
;;;; by WORK. The number of distinct root-to-leaf paths through a shared or
;;;; cyclic object graph is exponential in its depth, so a key with any sharing
;;;; costs exponentially to hash -- even though the key is `eq` to the one
;;;; already in the table, and even though the table was made `:test 'eq`.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar repro.lisp
;;;;
;;;; Reproduces on the compiled JVM class and on WASM as well; see ../README.md
;;;; result 5.

(defun ms () (get-internal-real-time))

(defmacro timed (label &rest body)
  `(let ((t0 (ms)))
     ,@body
     (format t "~a: ~a ms~%" ,label (- (ms) t0))
     (finish-output)))

;;; --- 1. a DAG key: no cycle at all, and the cost still doubles per level -----
;;;
;;; Each level is a cons whose car and cdr are BOTH the level below, so the
;;; structure holds n conses and 2^n root-to-leaf paths. Nothing here is cyclic
;;; and nothing is even mutated -- this is what an ordinary shared substructure
;;; costs.

(defvar *equal-table* (make-hash-table :test 'equal))

(defun shared-dag (depth)
  (let ((node 'leaf))
    (dotimes (i depth node)
      (setq node (cons node node)))))

(format t "an EQUAL table, keyed by a DAG of n conses (2^n paths):~%")
(dolist (n '(8 16 20 22 24 26))
  (let ((k (shared-dag n)))
    (timed (format nil "  n = ~2a  gethash" n) (gethash k *equal-table*))))

;;; --- 2. an EQ table, keyed by an object that knows its parent ----------------
;;;
;;; The shape every scene graph, doubly-linked list, parse tree with parent
;;; pointers or ORM entity has. `:test 'eq` is accepted and ignored -- the key is
;;; hashed structurally anyway (.todo/012) -- so the lookup walks the whole
;;; reachable graph, and the back-references make that walk exponential.

(defclass nd ()
  ((parent :initarg :parent :accessor parent-of :initform nil)
   (children :initform nil :accessor children-of)
   (payload :initform nil :accessor payload-of)))

(defun add-child (parent child)
  (setf (parent-of child) parent)
  (setf (children-of parent) (cons child (children-of parent)))
  child)

(defvar *eq-table* (make-hash-table :test 'eq))

;;; d = 1 is measured here. d = 2 does NOT return -- it was left running for 55 s
;;; and never answered -- which is why this loop stops at one: a probe you have to
;;; interrupt is not a measurement. Raise the bound to watch it happen.
(format t "~%an EQ table, keyed by a node in a chain of depth d:~%")
(let ((root (make-instance 'nd)))
  (dotimes (d 1)
    (let ((joint (make-instance 'nd))
          (leaf (make-instance 'nd)))
      (add-child root joint)
      (add-child joint leaf)
      (setq root joint)
      ;; The key is EQ to nothing in the table -- the table is empty. All of this
      ;; time is spent computing the key's hash.
      (timed (format nil "  d = ~a  gethash on an EMPTY table" (+ d 1))
             (gethash leaf *eq-table*)))))

(format t "~%reached the end~%")
