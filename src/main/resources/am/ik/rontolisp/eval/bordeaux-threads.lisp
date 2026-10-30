;; The bordeaux-threads packages (v1, nickname bt; and bt2, nickname
;; bordeaux-threads-2): the v1 API of bordeaux-threads 0.9 -- locks over the
;; built-in rontolisp:*-mutex primitives, condition variables over the internal
;; rontolisp::%condition-* primitives, semaphores over both, threads over the
;; built-in rontolisp:make-thread primitives -- with the v2 names that mean the
;; same, satisfying the built-in ASDF system "bordeaux-threads". Upstream is a per-implementation portability
;; layer whose own .asd hard-errors on an unknown implementation, so it cannot
;; support rontolisp from its side -- a shim is the only route. Written in
;; canonical shape; both packages (and the cross-package import redirects that
;; make each API's names resolve from the other package too) are seeded in
;; PackageRegistry.
;;
;; with-lock-held is NOT here: it is a built-in LispMacroExpander expansion
;; dispatched on its qualified name (the usocket:with-* pattern) onto
;; acquire-lock / release-lock below, so the same acquire / body /
;; release-on-unwind lowering serves every backend.
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
;;   on too). current-thread, all-threads, thread-name and condition-wait signal
;;   there as well (condition-wait could only wait forever); condition-notify
;;   and condition-broadcast wake nobody, so a semaphore counts and a wait on a
;;   positive count returns at once.
;; - interrupt-thread and with-timeout SIGNAL on every backend, as upstream does
;;   on an implementation without them: no backend can run code inside another
;;   running thread.
;; - make-thread's :initial-bindings (and *default-special-bindings*) is an
;;   alist of (symbol . form) pairs whose forms upstream evaluates in the new
;;   thread. The shim supports quote forms and self-evaluating values -- whose
;;   value is thread-independent, so where they are evaluated is unobservable
;;   -- and signals on any other form (a variable-reference form would need the
;;   new thread's dynamic environment). clack's bindings are exactly quotes and
;;   already-evaluated stream values.
;; - make-thread's :name rides the handle (thread-name); :trap-conditions is
;;   accepted and ignored (the error is stored in the handle and re-signaled at
;;   join-thread on every path here).
;; - all-threads answers the thread that loaded the system and the live threads
;;   make-thread spawned, not threads spawned by other means (a served request).
;; - make-lock returns a REENTRANT lock, where upstream's is not (upstream's
;;   make-recursive-lock is). That is a superset: a program that would deadlock
;;   on a real bordeaux-threads merely proceeds here. A lock is a structure over
;;   the mutex handle, so it has a class of its own (serapeum specializes a
;;   method on (class-of (bt:make-recursive-lock))).
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

;;; --- locks -------------------------------------------------------------------

(defstruct (bordeaux-threads:lock
            (:constructor bordeaux-threads::%make-lock (mutex))
            (:predicate bordeaux-threads:lock-p)
            (:conc-name bordeaux-threads::%lock-) (:copier nil))
  mutex)

;; make-lock is already reentrant here, so a recursive lock IS a plain lock;
;; the pair exists upstream only because ITS make-lock is not.
(deftype bordeaux-threads:recursive-lock () 'bordeaux-threads:lock)

;; &rest rather than &optional: the v1 spelling passes a positional name,
;; the v2 spelling (bt2:make-lock :name "...") passes a keyword pair -- both
;; resolve to this ONE symbol through the package import redirects, and the
;; name is ignored either way (a debugging label upstream).
(defun bordeaux-threads:make-lock (&rest name)
  (declare (ignore name))
  (bordeaux-threads::%make-lock (rontolisp:make-mutex)))

(defun bordeaux-threads:make-recursive-lock (&rest name)
  (declare (ignore name))
  (bordeaux-threads::%make-lock (rontolisp:make-mutex)))

(defun bordeaux-threads:recursive-lock-p (object)
  (bordeaux-threads:lock-p object))

(defun bordeaux-threads:acquire-lock (lock &optional (wait-p t))
  (rontolisp:mutex-acquire (bordeaux-threads::%lock-mutex lock))
  t)

(defun bordeaux-threads:release-lock (lock)
  (rontolisp:mutex-release (bordeaux-threads::%lock-mutex lock))
  t)

(defun bordeaux-threads:acquire-recursive-lock (lock)
  (bordeaux-threads:acquire-lock lock))

(defun bordeaux-threads:release-recursive-lock (lock)
  (bordeaux-threads:release-lock lock))

;;; --- condition variables -------------------------------------------------------

(defstruct (bordeaux-threads::condition-variable
            (:constructor bordeaux-threads::%make-condition-variable (handle))
            (:predicate bordeaux-threads::condition-variable-p)
            (:conc-name bordeaux-threads::%condition-variable-) (:copier nil))
  handle)

#-rontolisp-wasm
(defun bordeaux-threads:make-condition-variable (&key name)
  (bordeaux-threads::%make-condition-variable
   (rontolisp::%condition-variable-new)))

;; Releases LOCK (every hold), waits for a notify, takes LOCK back and answers
;; t -- or nil once TIMEOUT seconds passed without one. A wakeup may be
;; spurious, as upstream allows: a caller re-tests its predicate in a loop.
#-rontolisp-wasm
(defun bordeaux-threads:condition-wait (condition-variable lock &key timeout)
  (rontolisp::%condition-wait
   (bordeaux-threads::%condition-variable-handle condition-variable)
   (bordeaux-threads::%lock-mutex lock)
   (and timeout (max 0 (round (* timeout 1000000000))))))

#-rontolisp-wasm
(defun bordeaux-threads:condition-notify (condition-variable)
  (rontolisp::%condition-notify
   (bordeaux-threads::%condition-variable-handle condition-variable) nil))

#-rontolisp-wasm
(defun bordeaux-threads:condition-broadcast (condition-variable)
  (rontolisp::%condition-notify
   (bordeaux-threads::%condition-variable-handle condition-variable) t))

#+rontolisp-wasm
(defun bordeaux-threads:make-condition-variable (&key name)
  (bordeaux-threads::%make-condition-variable nil))

#+rontolisp-wasm
(defun bordeaux-threads:condition-wait (condition-variable lock &key timeout)
  (error
   "bt:condition-wait: the WASM backends are single-threaded by construction; no other thread can notify"))

#+rontolisp-wasm
(defun bordeaux-threads:condition-notify (condition-variable) nil)

#+rontolisp-wasm
(defun bordeaux-threads:condition-broadcast (condition-variable) nil)

;;; --- semaphores ----------------------------------------------------------------

;; Upstream's own portable semaphore: a count under a lock, and a condition
;; variable a waiter sleeps on while the count is zero.
(defstruct (bordeaux-threads:semaphore
            (:constructor bordeaux-threads::%make-semaphore
                          (name count lock condition-variable))
            (:predicate bordeaux-threads:semaphore-p)
            (:conc-name bordeaux-threads::%semaphore-) (:copier nil))
  name
  count
  lock
  condition-variable)

(defun bordeaux-threads:make-semaphore (&key name (count 0))
  (bordeaux-threads::%make-semaphore name count (bordeaux-threads:make-lock)
   (bordeaux-threads:make-condition-variable)))

(defun bordeaux-threads:signal-semaphore (semaphore &key (count 1))
  (bordeaux-threads:with-lock-held ((bordeaux-threads::%semaphore-lock
                                     semaphore))
    (incf (bordeaux-threads::%semaphore-count semaphore) count)
    (loop repeat
          count
          do
            (bordeaux-threads:condition-notify
             (bordeaux-threads::%semaphore-condition-variable semaphore))))
  nil)

;; t once the count was decremented, nil when TIMEOUT seconds passed first.
(defun bordeaux-threads:wait-on-semaphore (semaphore &key timeout)
  (let ((deadline
         (and timeout
              (+ (get-internal-real-time)
                 (* timeout internal-time-units-per-second))))
        (lock (bordeaux-threads::%semaphore-lock semaphore))
        (cv (bordeaux-threads::%semaphore-condition-variable semaphore)))
    (bordeaux-threads:with-lock-held (lock)
      (loop
        (when (plusp (bordeaux-threads::%semaphore-count semaphore))
          (decf (bordeaux-threads::%semaphore-count semaphore))
          (return t))
        (if deadline
            (let ((remaining
                   (/ (- deadline (get-internal-real-time))
                      internal-time-units-per-second)))
              (unless (plusp remaining) (return nil))
              (bordeaux-threads:condition-wait cv lock :timeout remaining))
            (bordeaux-threads:condition-wait cv lock))))))

;;; --- threads -------------------------------------------------------------------

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
         (error "bt2:make-thread: unsupported :initial-bindings value form: ~A"
                form))
        ((consp form)
         (error "bt2:make-thread: unsupported :initial-bindings value form: ~A"
                form))
        (t form)))

;; The threads make-thread spawned, for all-threads: each spawn drops the dead
;; ones, so the list holds the live threads plus those that died since the
;; last spawn. The spawn and its registration share the lock, so a thread
;; cannot be missed between the two.
#-rontolisp-wasm (defvar bt2::*threads-lock* (rontolisp:make-mutex))
#-rontolisp-wasm (defvar bt2::*threads* nil)
#-rontolisp-wasm (defvar bt2::*main-thread* (rontolisp:current-thread))

#-rontolisp-wasm
(defun bt2:make-thread (function &key name
                        (initial-bindings bt2:*default-special-bindings*)
                        trap-conditions)
  (let ((bindings
         (mapcar (lambda (pair)
                   (cons (car pair) (bt2::resolve-binding-value (cdr pair))))
                 initial-bindings)))
    (rontolisp:with-mutex (bt2::*threads-lock*)
      (let ((thread (rontolisp:make-thread function bindings name)))
        (setq bt2::*threads*
              (cons thread
                    (remove-if-not (lambda (th) (rontolisp:thread-alive-p th))
                                   bt2::*threads*)))
        thread))))

#-rontolisp-wasm
(defun bordeaux-threads:all-threads ()
  (rontolisp:with-mutex (bt2::*threads-lock*)
    (cons bt2::*main-thread*
          (remove-if-not (lambda (th) (rontolisp:thread-alive-p th))
                         bt2::*threads*))))

;; The :name make-thread was given; nil for none, and for a thread it did not
;; spawn.
#-rontolisp-wasm
(defun bordeaux-threads:thread-name (thread) (rontolisp::%thread-name thread))

#-rontolisp-wasm
(defun bordeaux-threads:thread-yield ()
  (sleep 0)
  nil)

#-rontolisp-wasm (defun bt2:join-thread (thread) (rontolisp:join-thread thread))

#-rontolisp-wasm (defun bt2:threadp (object) (rontolisp:threadp object))

#-rontolisp-wasm
(defun bt2:thread-alive-p (thread) (rontolisp:thread-alive-p thread))

#-rontolisp-wasm
(defun bt2:destroy-thread (thread) (rontolisp:destroy-thread thread))

;; The calling thread's own handle, EQ-stable per thread (the primitive caches
;; it in a ThreadLocal), so it can key an eq hash table -- dbi's per-thread
;; connection cache (cache/thread.lisp's steal-cache-table) is the driving
;; consumer. Works for the main thread and served requests too, not only
;; make-thread spawns.
#-rontolisp-wasm (defun bt2:current-thread () (rontolisp:current-thread))

#+rontolisp-wasm
(defun bt2:make-thread (function &key name initial-bindings trap-conditions)
  (error
   "bt2:make-thread: the WASM backends are single-threaded by construction; run without threads (clack:clackup takes :use-thread nil)"))

#+rontolisp-wasm
(defun bt2:join-thread (thread)
  (error
   "bt2:join-thread: the WASM backends are single-threaded by construction"))

#+rontolisp-wasm (defun bt2:threadp (object) nil)

#+rontolisp-wasm
(defun bt2:thread-alive-p (thread)
  ;; No thread handle can exist on this backend, so every argument is the same
  ;; non-thread the interpreter and the JVM signal on -- the same type-error and
  ;; report for every wasm-reachable input, not a divergence.
  (error 'type-error
   :datum thread
   :expected-type '(satisfies rontolisp:threadp)
   :format-control
   "THREAD-ALIVE-P: The value ~S is not of type (SATISFIES RONTOLISP:THREADP)"
   :format-arguments (list thread)))

#+rontolisp-wasm
(defun bt2:destroy-thread (thread)
  (error
   "bt2:destroy-thread: the WASM backends are single-threaded by construction"))

;; Signals like the other wasm thread entry points. Nothing bundled reaches it:
;; the dbi override .asd selects the single-threaded cache there (single.lisp,
;; upstream's own choice for a threadless implementation), which never asks.
#+rontolisp-wasm
(defun bt2:current-thread ()
  (error
   "bt2:current-thread: the WASM backends are single-threaded by construction"))

#+rontolisp-wasm
(defun bordeaux-threads:all-threads ()
  (error
   "bt:all-threads: the WASM backends are single-threaded by construction"))

#+rontolisp-wasm
(defun bordeaux-threads:thread-name (thread)
  ;; No thread handle exists here: thread-alive-p's report, for the same reason.
  (error 'type-error
   :datum thread
   :expected-type '(satisfies rontolisp:threadp)
   :format-control
   "THREAD-NAME: The value ~S is not of type (SATISFIES RONTOLISP:THREADP)"
   :format-arguments (list thread)))

#+rontolisp-wasm (defun bordeaux-threads:thread-yield () nil)

;;; --- the rest of the v1 export list ----------------------------------------------

(deftype bordeaux-threads:thread () '(satisfies bt2:threadp))

(defun bordeaux-threads:start-multiprocessing () nil)

(defun bordeaux-threads:interrupt-thread (thread function &rest args)
  (error
   "bt:interrupt-thread is not supported: no rontolisp backend can run a function inside another running thread"))

(define-condition bordeaux-threads:timeout (serious-condition)
  ((length :initform nil :initarg :length)))

(defmacro bordeaux-threads:with-timeout ((timeout) &body body)
  (declare (ignore body))
  `(progn
     ,timeout
     (error
      "bt:with-timeout is not supported: no rontolisp backend can interrupt a running body")))

;; Upstream's alist of the standard I/O variables at their standard values, for
;; :initial-bindings. Upstream's *package*, *readtable*, *random-state* and
;; *print-pprint-dispatch* entries are forms evaluated in the new thread, which
;; :initial-bindings does not support here.
(defvar bordeaux-threads:*standard-io-bindings*
  '((*print-array* . t) (*print-base* . 10) (*print-case* . :upcase)
    (*print-circle* . nil) (*print-escape* . t) (*print-gensym* . t)
    (*print-length* . nil) (*print-level* . nil) (*print-lines* . nil)
    (*print-miser-width* . nil) (*print-pretty* . nil) (*print-radix* . nil)
    (*print-readably* . t) (*print-right-margin* . nil) (*read-base* . 10)
    (*read-default-float-format* . 'single-float) (*read-eval* . t)
    (*read-suppress* . nil)))
