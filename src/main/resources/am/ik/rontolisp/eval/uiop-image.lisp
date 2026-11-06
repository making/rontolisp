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
;;;; * The IMAGE is a program. The interpreter dumps one by COMPILING it: the
;;;;   systems the image loaded, then the start a restored image runs -- restore-image,
;;;;   which is real on every backend because a compiled program starts fresh, exactly
;;;;   the restored case. A compiled program has no compiler to dump with, and there are
;;;;   no object files to create an image from, so those two refuse by name. The hooks
;;;;   are real lists a library registers into at load time.

;;; The variables. Upstream's defaults, except *lisp-interaction*.
;;
;; *lisp-interaction* is NIL here where upstream defaults to T: it asks "is this an
;; interactive Lisp environment, or is it batch processing?", and every rontolisp
;; backend runs a program and ends -- there is no debugger to enter (rontolisp has no
;; invoke-debugger at all) and no REPL underneath a compiled artifact. The value is
;; what makes handle-fatal-condition report and die instead of calling a debugger
;; that does not exist.
(defvar uiop/image:*lisp-interaction* nil)

;; Nil in a program that was started; a dumped program sets it before it restores
;; (:executable), as the heap upstream saves carries it.
(defvar uiop/image:*image-dumped-p* nil)

;; Upstream's internal state of the restore, which deploy reads with `::`.
(defvar uiop/image::*image-restored-p* nil)

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
(defun uiop/image:register-image-restore-hook
    (%rirh-hook &optional (%rirh-call-now-p t))
  (uiop/utility:register-hook-function 'uiop/image:*image-restore-hook*
                                       %rirh-hook %rirh-call-now-p))

(defun uiop/image:register-image-dump-hook
    (%ridh-hook &optional %ridh-call-now-p)
  (uiop/utility:register-hook-function 'uiop/image:*image-dump-hook* %ridh-hook
                                       %ridh-call-now-p))

(defun uiop/image:call-image-restore-hook ()
  (uiop/utility:call-functions (reverse uiop/image:*image-restore-hook*)))

(defun uiop/image:call-image-dump-hook ()
  (uiop/utility:call-functions uiop/image:*image-dump-hook*))

;;; The image itself. A dumped image is a compiled program (see the header), so
;;; restore-image is upstream's start and dump-image the interpreter's compile.

;; Upstream's body: the restore hooks, the prelude, then the entry point, whose
;; first value is the exit status when nothing interactive is underneath (nil here,
;; *lisp-interaction*). A dumped program ends in a call of this.
(defun uiop/image:restore-image (&key ((:lisp-interaction %ri-interaction)
                                       uiop/image:*lisp-interaction*)
                                      ((:restore-hook %ri-restore-hook)
                                       uiop/image:*image-restore-hook*)
                                      ((:prelude %ri-prelude)
                                       uiop/image:*image-prelude*)
                                      ((:entry-point %ri-entry-point)
                                       uiop/image:*image-entry-point*)
                                      ((:if-already-restored %ri-if-restored)
                                       '(cerror "RUN RESTORE-IMAGE ANYWAY")))
  (when (and uiop/image::*image-restored-p* %ri-if-restored)
    (uiop/utility:call-function %ri-if-restored
                                "Image already ~:[being ~;~]restored"
                                (eq uiop/image::*image-restored-p* t)))
  (unless (and uiop/image::*image-restored-p* (null %ri-if-restored))
    (uiop/image:call-with-fatal-condition-handler
     (lambda ()
       (setf uiop/image:*lisp-interaction* %ri-interaction)
       (setf uiop/image:*image-restore-hook* %ri-restore-hook)
       (setf uiop/image:*image-prelude* %ri-prelude)
       (setf uiop/image::*image-restored-p* :in-progress)
       (uiop/image:call-image-restore-hook)
       (uiop/stream:standard-eval-thunk %ri-prelude)
       (setf uiop/image::*image-restored-p* t)
       (let ((%ri-results
              (multiple-value-list
               (if %ri-entry-point
                   (uiop/utility:call-function %ri-entry-point)
                   t))))
         (if %ri-interaction
             (values-list %ri-results)
             (uiop/image:shell-boolean-exit (first %ri-results))))))))

;; Upstream's protocol around the dump (the postlude, the dump hooks, the two
;; flags), run by the %dump-image primitive once it knows it can dump: the
;; interpreter's compiles the image -- the systems it loaded and the restore of
;; *image-entry-point* -- to the artifact FILENAME names (no type is a native
;; executable, .jar a JVM program, .wasm a module); a compiled program's refuses,
;; having no compiler.
(defun uiop/image:dump-image (%di-filename &key ((:output-name %di-output-name))
                                           ((:executable %di-executable))
                                           ((:postlude %di-postlude)
                                            uiop/image:*image-postlude*)
                                           ((:dump-hook %di-dump-hook)
                                            uiop/image:*image-dump-hook*))
  (declare (ignore %di-output-name))
  (%dump-image (namestring %di-filename) %di-executable
               (lambda ()
                 (setf uiop/image:*image-dumped-p*
                       (if %di-executable :executable t))
                 (setf uiop/image::*image-restored-p* :in-regress)
                 (setf uiop/image:*image-postlude* %di-postlude)
                 (uiop/stream:standard-eval-thunk uiop/image:*image-postlude*)
                 (setf uiop/image:*image-dump-hook* %di-dump-hook)
                 (uiop/image:call-image-dump-hook)
                 (setf uiop/image::*image-restored-p* nil))))

(defun uiop/image:create-image (%ci-destination %ci-object-files &rest %ci-keys)
  (declare (ignore %ci-destination %ci-object-files %ci-keys))
  (uiop/utility:not-implemented-error "UIOP/IMAGE:CREATE-IMAGE"
                                      "there are no lisp object files to link: the compile backends take one program and emit one artifact"))

;;; The command line. One shape on all four backends, from the %host-argv primitive:
;;; the vector is (program-name user-arg ...), so command-line-arguments is its rest
;;; and argv0 its first, with no per-backend arm. Where each backend's vector comes
;;; from is LispNames.HOST_ARGV's business; what the five names MEAN is here.
;;
;; Upstream reaches the same two answers through *image-dumped-p* being :executable
;; -- an implementation that was dumped gets its own argv, everything else has to
;; find the "--" a wrapper script left in a vector that still holds the
;; implementation's own arguments. Nothing dumps here and *image-dumped-p* stays nil
;; (see above), so this divergence is deliberate: rontolisp is always the executable
;; case, because the CLI keeps its own options out of the vector (it splits them at
;; the -- separator itself) and a compiled artifact only ever sees the program's.
(defun uiop/image:raw-command-line-arguments () (%host-argv))

(defun uiop/image:argv0 () (first (%host-argv)))

(defun uiop/image:command-line-arguments
    (&optional (%cla-arguments (uiop/image:raw-command-line-arguments)))
  (rest %cla-arguments))

(defun uiop/image:setup-command-line-arguments ()
  (setf uiop/image:*command-line-arguments*
        (uiop/image:command-line-arguments)))

;; SEEDED, where upstream's default is nil: upstream fills it from restore-image, the
;; one thing no backend here can do, so a nil default would leave the variable a trap
;; -- read for the arguments it names and answering nothing, forever, with nowhere to
;; call setup-command-line-arguments from. A program starts fresh on every backend,
;; which is exactly the restored-image case, so the initial value is that call's.
(defvar uiop/image:*command-line-arguments* (uiop/image:command-line-arguments))
