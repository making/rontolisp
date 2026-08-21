;;;; uiop/image -- exiting, fatal conditions, backtraces and the image hooks.
;;;;
;;;; Canonical shape; see .kb/uiop.md. Three groups with three different answers:
;;;;
;;;; * EXIT is real on all four backends. quit finishes the standard output streams
;;;;   and calls the %host-exit primitive -- a signal the CLI turns into the process
;;;;   code on the interpreter, System.exit on the JVM, proc_exit on WASM Preview 1
;;;;   and wasi:cli/exit's exit-with-code under --component (exit.lisp) -- so the ONE
;;;;   definition below is what quitting means everywhere.
;;;; * BACKTRACES are lite and stay lite: no backend carries a Lisp-level call stack,
;;;;   so the honest rendering of "the backtrace for this condition" is the condition
;;;;   and no frames. Real UIOP's own fallback for an implementation with no backtrace
;;;;   API has the same shape. The fatal-condition quartet on top of them is real: it
;;;;   is *lisp-interaction*-gated error reporting, not frame walking.
;;;; * The IMAGE itself cannot be dumped, restored or created here -- there is no
;;;;   image, only a program and the artifact it compiled to -- so those three name
;;;;   what is missing. The HOOKS are real anyway: they are just lists, a library may
;;;;   register into one at load time, and only the act of dumping is impossible.

;;; The variables. Upstream's defaults, except *lisp-interaction*.
;;
;; *lisp-interaction* is NIL here where upstream defaults to T: it asks "is this an
;; interactive Lisp environment, or is it batch processing?", and every rontolisp
;; backend runs a program and ends -- there is no debugger to enter (rontolisp has no
;; invoke-debugger at all) and no REPL underneath a compiled artifact. The value is
;; what makes handle-fatal-condition report and die instead of calling a debugger
;; that does not exist.
(defvar uiop/image:*lisp-interaction* nil)

;; No backend can dump an image (dump-image below says so), so this is nil forever --
;; upstream's own value for a Lisp that was started, not restored.
(defvar uiop/image:*image-dumped-p* nil)

(defvar uiop/image:*image-restore-hook* nil)

(defvar uiop/image:*image-dump-hook* nil)

(defvar uiop/image:*image-prelude* nil)

(defvar uiop/image:*image-entry-point* nil)

(defvar uiop/image:*image-postlude* nil)

;;; Exiting.
;;
;; The code is masked to eight bits, which is what a POSIX host does with it anyway
;; (exit(3) keeps the low byte) and what wasi:cli/exit's u8 status accepts -- so the
;; four backends agree on the answer instead of agreeing only up to 255.
;;
;; finish-output is upstream's obligation and matters most where the output is
;; buffered: without it the last lines of a program that quits are simply lost. It is
;; spelled over the CL streams rather than over uiop/stream's *stdout* / *stderr*,
;; which are not implemented yet -- when they are, this becomes
;; (finish-outputs).
;;
;; quit does NOT unwind: System.exit, proc_exit and exit-with-code all end the process
;; where they stand, and the interpreter's exit signal is deliberately invisible to
;; unwind-protect for that reason. Nothing runs after a quit, on any backend.
(defun uiop/image:quit (&optional (%q-code 0) (%q-finish-output t))
  (when %q-finish-output
    (finish-output *standard-output*)
    (finish-output *error-output*))
  (%host-exit (logand %q-code 255)))

(defun uiop/image:die (%d-code %d-format &rest %d-arguments)
  (format *error-output* "~&~?~&" %d-format %d-arguments)
  (uiop/image:quit %d-code))

(defun uiop/image:shell-boolean-exit (%sbe-x) (uiop/image:quit (if %sbe-x 0 1)))

;;; Backtraces -- the condition, and no frames (see the header).
(defun uiop/image:raw-print-backtrace (&key (stream *debug-io*) count condition)
  (declare (ignore count))
  (when condition (format stream "~A~%" condition))
  (values))

(defun uiop/image:print-backtrace (&rest %pb-keys &key stream count condition)
  (declare (ignore stream count condition))
  (apply #'uiop/image:raw-print-backtrace %pb-keys))

;; Upstream prints the frames and then "Above backtrace due to this condition:";
;; there are no frames to be above, so this is the condition alone.
;; lack-middleware-backtrace calls it as the first line of its error report.
(defun uiop/image:print-condition-backtrace
    (%pcb-condition &key (stream *error-output*) count)
  (uiop/image:print-backtrace :stream stream
                              :count count
                              :condition %pcb-condition))

;;; Fatal conditions: a serious-condition nobody handled, reported and turned into an
;;; exit status. Upstream additionally excludes Clozure's process-reset.
(deftype uiop/image:fatal-condition () 'serious-condition)

(defun uiop/image:fatal-condition-p (%fcp-condition)
  (typep %fcp-condition 'uiop/image:fatal-condition))

;; Upstream enters the debugger when *lisp-interaction* is true. There is no
;; invoke-debugger on any backend here, and the variable is nil above, so the
;; interactive arm names what it would need instead of pretending to offer it.
(defun uiop/image:handle-fatal-condition (%hfc-condition)
  (if uiop/image:*lisp-interaction*
      (uiop/utility:not-implemented-error "UIOP/IMAGE:HANDLE-FATAL-CONDITION"
                                          "*lisp-interaction* is true, which asks for invoke-debugger; no backend has a debugger to enter")
      (progn
        (format *error-output* "~&Fatal condition:~%~A~%" %hfc-condition)
        (uiop/image:print-condition-backtrace %hfc-condition
                                              :stream *error-output*)
        (uiop/image:die 99 "~A" %hfc-condition))))

(defun uiop/image:call-with-fatal-condition-handler (%cwfch-thunk)
  (handler-bind ((uiop/image:fatal-condition
                  #'uiop/image:handle-fatal-condition))
    (funcall %cwfch-thunk)))

;;; The image hooks. Real lists with real registration, because a library may push
;;; onto one at load time and only the DUMP is impossible.
;;
;; Upstream routes both through (register-hook-function '*image-dump-hook* ...),
;; which pushes onto a variable named at RUN time -- (setf (symbol-value var) ...),
;; not a place on any backend, which is why uiop/utility's register-hook-function
;; signals. Naming the variable literally is the same registration without that
;; primitive. Re-evaluation trigger: the day (setf (symbol-value ...)) is a place,
;; both bodies become the one register-hook-function call upstream writes.
(defun uiop/image:register-image-restore-hook
    (%rirh-hook &optional (%rirh-call-now-p t))
  (pushnew %rirh-hook uiop/image:*image-restore-hook* :test 'equal)
  (when %rirh-call-now-p (uiop/utility:call-function %rirh-hook))
  (values))

(defun uiop/image:register-image-dump-hook
    (%ridh-hook &optional %ridh-call-now-p)
  (pushnew %ridh-hook uiop/image:*image-dump-hook* :test 'equal)
  (when %ridh-call-now-p (uiop/utility:call-function %ridh-hook))
  (values))

(defun uiop/image:call-image-restore-hook ()
  (uiop/utility:call-functions (reverse uiop/image:*image-restore-hook*)))

(defun uiop/image:call-image-dump-hook ()
  (uiop/utility:call-functions uiop/image:*image-dump-hook*))

;;; The image itself. There is none: rontolisp starts a program, runs it and ends,
;;; and the compile backends emit an artifact from source rather than saving a heap.
;;; Each of the three names the missing capability rather than half-doing it.
(defun uiop/image:restore-image (&rest %ri-keys)
  (declare (ignore %ri-keys))
  (uiop/utility:not-implemented-error "UIOP/IMAGE:RESTORE-IMAGE"
                                      "no backend restores a dumped image: a program is started from source, never resumed"))

(defun uiop/image:dump-image (%di-filename &rest %di-keys)
  (declare (ignore %di-filename %di-keys))
  (uiop/utility:not-implemented-error "UIOP/IMAGE:DUMP-IMAGE"
                                      "no backend can save its heap: compile the program instead (rontolisp file.lisp -o out)"))

(defun uiop/image:create-image (%ci-destination %ci-object-files &rest %ci-keys)
  (declare (ignore %ci-destination %ci-object-files %ci-keys))
  (uiop/utility:not-implemented-error "UIOP/IMAGE:CREATE-IMAGE"
                                      "there are no lisp object files to link: the compile backends take one program and emit one artifact"))
