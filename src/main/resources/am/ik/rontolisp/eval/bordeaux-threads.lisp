;; The bordeaux-threads package (nickname bt): the LOCKING subset of the v1 API,
;; over the built-in rontolisp:*-mutex primitives, satisfying the built-in ASDF
;; system "bordeaux-threads". Upstream is a per-implementation portability layer
;; whose own .asd hard-errors on an unknown implementation, so it cannot support
;; rontolisp from its side -- a shim is the only route. Written in canonical
;; shape; the package and its bt nickname are seeded in PackageRegistry.
;;
;; with-lock-held is NOT here: it is a built-in LispMacroExpander expansion
;; dispatched on its qualified name (the usocket:with-* pattern), so the same
;; acquire / body / release-on-unwind lowering serves every backend.
;;
;; Compatibility notes:
;; - Thread CREATION (make-thread, join-thread, threadp, ...) is deliberately
;;   absent: no backend can spawn a thread from Lisp, and a shim that pretended
;;   otherwise would turn a compile-time error into a silently sequential
;;   program. rontolisp runs concurrent code all the same -- rontolisp:serve /
;;   http-handler put one virtual thread per request on the interpreter and the
;;   JVM -- which is exactly why the locks below have to be real.
;; - make-lock returns a REENTRANT lock, where upstream's is not (upstream's
;;   make-recursive-lock is). That is a superset: a program that would deadlock
;;   on a real bordeaux-threads merely proceeds here.
;; - The lock name is accepted and ignored (it is a debugging label upstream).
;; - acquire-lock's :wait-p is accepted and ignored: the acquisition always
;;   blocks, so a (acquire-lock l nil) caller that expects an immediate nil on
;;   contention instead waits. No caller in the loadable corpus passes it.

(defparameter bordeaux-threads:*supports-threads-p*
  ;; A claim libraries act on, so it is per-backend and not decoration: the
  ;; interpreter and the JVM really run one virtual thread per served request,
  ;; while both WASM backends are single-threaded by construction.
  #+rontolisp-wasm nil
  #-rontolisp-wasm t)

(defun bordeaux-threads:make-lock (&optional name)
  (rontolisp:make-mutex))

(defun bordeaux-threads:acquire-lock (lock &optional (wait-p t))
  (rontolisp:mutex-acquire lock)
  t)

(defun bordeaux-threads:release-lock (lock)
  (rontolisp:mutex-release lock)
  t)
