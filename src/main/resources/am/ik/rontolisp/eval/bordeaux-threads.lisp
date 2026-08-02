;; The bordeaux-threads packages (v1, nickname bt; and bt2, nickname
;; bordeaux-threads-2): the locking subset of the v1 API over the built-in
;; rontolisp:*-mutex primitives, plus the thread-creation subset of the v2 API
;; over the built-in rontolisp:make-thread primitives, satisfying the built-in
;; ASDF system "bordeaux-threads". Upstream is a per-implementation portability
;; layer whose own .asd hard-errors on an unknown implementation, so it cannot
;; support rontolisp from its side -- a shim is the only route. Written in
;; canonical shape; both packages (and the cross-package import redirects that
;; make each API's names resolve from the other package too) are seeded in
;; PackageRegistry.
;;
;; with-lock-held is NOT here: it is a built-in LispMacroExpander expansion
;; dispatched on its qualified name (the usocket:with-* pattern), so the same
;; acquire / body / release-on-unwind lowering serves every backend.
;;
;; Compatibility notes:
;; - Thread creation is REAL on the interpreter and the JVM backend
;;   (rontolisp:make-thread spawns a virtual thread; clack's handler.lisp is
;;   the driving consumer). On the WASM backends -- single-threaded by
;;   construction -- make-thread, join-thread and destroy-thread SIGNAL at call
;;   time (never run inline: a shim that pretended to spawn would turn a
;;   compile-time error into a silently sequential program), threadp answers
;;   nil, and thread-alive-p signals its ordinary not-a-thread error (no thread
;;   handle can exist there, so every argument is one the other backends signal
;;   on too).
;; - make-thread's :initial-bindings (and *default-special-bindings*) is an
;;   alist of (symbol . form) pairs whose forms upstream evaluates in the new
;;   thread. The shim supports quote forms and self-evaluating values -- whose
;;   value is thread-independent, so where they are evaluated is unobservable
;;   -- and signals on any other form (a variable-reference form would need the
;;   new thread's dynamic environment). clack's bindings are exactly quotes and
;;   already-evaluated stream values.
;; - make-thread's :name is accepted and ignored (a debugging label upstream);
;;   :trap-conditions is accepted and ignored (the error is stored in the
;;   handle and re-signaled at join-thread on every path here).
;; - make-lock returns a REENTRANT lock, where upstream's is not (upstream's
;;   make-recursive-lock is). That is a superset: a program that would deadlock
;;   on a real bordeaux-threads merely proceeds here.
;; - The lock name is accepted and ignored (it is a debugging label upstream).
;; - acquire-lock's :wait-p is accepted and ignored: the acquisition always
;;   blocks, so a (acquire-lock l nil) caller that expects an immediate nil on
;;   contention instead waits. No caller in the loadable corpus passes it.

(defparameter bordeaux-threads:*supports-threads-p*
  ;; A claim libraries act on, so it is per-backend and not decoration: the
  ;; interpreter and the JVM really spawn threads (make-thread, and one virtual
  ;; thread per served request), while both WASM backends are single-threaded
  ;; by construction.
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

(defvar bt2:*default-special-bindings* nil)

(defun bt2::resolve-binding-value (form)
  ;; One :initial-bindings value form -> its value. Quote forms and
  ;; self-evaluating values only (see the compatibility note above); a symbol
  ;; or any other compound form signals rather than binding the wrong value.
  (cond ((and (consp form) (eq (car form) 'quote)) (car (cdr form)))
        ((null form) nil)
        ((eq form t) t)
        ((keywordp form) form)
        ((symbolp form)
         (error "bt2:make-thread: unsupported :initial-bindings value form: ~A" form))
        ((consp form)
         (error "bt2:make-thread: unsupported :initial-bindings value form: ~A" form))
        (t form)))

#-rontolisp-wasm
(defun bt2:make-thread (function &key name (initial-bindings bt2:*default-special-bindings*) trap-conditions)
  (rontolisp:make-thread function
                         (mapcar (lambda (pair)
                                   (cons (car pair) (bt2::resolve-binding-value (cdr pair))))
                                 initial-bindings)))

#-rontolisp-wasm
(defun bt2:join-thread (thread)
  (rontolisp:join-thread thread))

#-rontolisp-wasm
(defun bt2:threadp (object)
  (rontolisp:threadp object))

#-rontolisp-wasm
(defun bt2:thread-alive-p (thread)
  (rontolisp:thread-alive-p thread))

#-rontolisp-wasm
(defun bt2:destroy-thread (thread)
  (rontolisp:destroy-thread thread))

#+rontolisp-wasm
(defun bt2:make-thread (function &key name initial-bindings trap-conditions)
  (error "bt2:make-thread: the WASM backends are single-threaded by construction; run without threads (clack:clackup takes :use-thread nil)"))

#+rontolisp-wasm
(defun bt2:join-thread (thread)
  (error "bt2:join-thread: the WASM backends are single-threaded by construction"))

#+rontolisp-wasm
(defun bt2:threadp (object)
  nil)

#+rontolisp-wasm
(defun bt2:thread-alive-p (thread)
  ;; No thread handle can exist on this backend, so every argument is the same
  ;; non-thread the interpreter and the JVM signal on -- identical behavior for
  ;; every wasm-reachable input, not a divergence.
  (error "THREAD-ALIVE-P expects a thread handle, got ~A" thread))

#+rontolisp-wasm
(defun bt2:destroy-thread (thread)
  (error "bt2:destroy-thread: the WASM backends are single-threaded by construction"))
