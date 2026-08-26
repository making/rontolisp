;; The trivial-garbage package: a shim satisfying the built-in ASDF system
;; "trivial-garbage" (dbd-postgres's dependency). The real library is a
;; per-implementation portability layer over GC finalizers, weak hash tables
;; and weak pointers; its .asd opens with a reader-conditional (error "Sorry,
;; your Lisp is not supported...") that survives under rontolisp's features,
;; so the file cannot be parsed as data at all -- and no backend exposes GC
;; finalization hooks to build the real thing on anyway.
;;
;; Written in canonical shape; the package and its nickname (tg) are seeded in
;; PackageRegistry. Only the two members dbd-postgres imports are defined:
;;
;; - finalize registers NOTHING and returns the object (upstream's return
;;   value). This is honest, not a lie: Common Lisp gives finalizers no
;;   guarantee of ever running, so a conforming consumer must already work
;;   when they never fire. dbd-postgres uses them opportunistically, to queue
;;   deallocation of prepared statements a caller leaked -- with the shim the
;;   statements live until the connection closes, and the documented contract
;;   is the one that was always required: call dbi:disconnect explicitly.
;; - cancel-finalization is the matching no-op (nothing was registered).
(defun trivial-garbage:finalize (object function)
  (declare (ignore function))
  object)

(defun trivial-garbage:cancel-finalization (object)
  (declare (ignore object))
  nil)

;; A weak hash table degrades to an ORDINARY one. Weakness is not observable
;; from within Common Lisp -- a program cannot tell whether an entry it never
;; looks at was collected -- so the only difference is that the table retains
;; more, which is the same trade the finalizer no-op above makes. :weakness
;; and :weakness-matters are dropped and the remaining arguments reach
;; make-hash-table, so :test carries through.
(defun trivial-garbage:make-weak-hash-table (&rest args)
  (let ((plain nil))
    (do ((tail args (cddr tail)))
        ((null tail))
      (unless (member (car tail) '(:weakness :weakness-matters))
        (setq plain (append plain (list (car tail) (cadr tail))))))
    (apply #'make-hash-table plain)))

;; Nothing here is weak, so the accessor says so.
(defun trivial-garbage:hash-table-weakness (table)
  (declare (ignore table))
  nil)
