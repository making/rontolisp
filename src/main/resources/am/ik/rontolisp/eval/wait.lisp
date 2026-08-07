;;;; wait.lisp -- rontolisp:wait-for over wit-imported wasi:clocks/monotonic-clock@0.3.0.
;;;;
;;;; This is the --component implementation (spliced by eval/WaitForLibrary when a
;;;; --component program references rontolisp:wait-for). The interpreter and the JVM
;;;; keep their CompletableFuture.completeOnTimeout timer; Preview 1 has no host timer.
;;;;
;;;; `wait-for` is an `async func`, so its binding returns a FIRST-CLASS FUTURE
;;;; (rontolisp::%subtask-future over the async-lowered call): pending while the host
;;;; timer runs, settled to nil by the scheduler when the subtask returns -- so
;;;; concurrent timers genuinely overlap, matching the interpreter/JVM contract.
;;;; The component binds the interface against the fixed import block's own
;;;; monotonic-clock instance (WasmComponentBuilder lowers it FROM the block; a second
;;;; import of the same interface would be invalid).

;; The WIT interface is lowered by WaitForLibrary (which calls WitImportDirective.lower
;; itself), so this directive never reaches WitImportInliner -- but it is written here,
;; in wait.lisp's own source, so the file reads as the program it is.
(rontolisp:wit-import "clocks.wit"
                      :interface "wasi:clocks/monotonic-clock@0.3.0"
                      :package %mono-clock)

(defun rontolisp:wait-for (%wait-for-ms)
  ;; The host timer takes nanoseconds (duration = u64); the Lisp surface takes
  ;; non-negative integer milliseconds, like the interpreter/JVM built-in.
  (if (if (integerp %wait-for-ms) (>= %wait-for-ms 0) nil)
      (%mono-clock:wait-for (* %wait-for-ms 1000000))
      (error
       "rontolisp:wait-for expects a non-negative integer (milliseconds)")))

;; cl:sleep on this backend, over the same host timer. It FORCES the timer future
;; rather than awaiting it, which is what lets it stay an ordinary synchronous defun:
;; an `await` is only legal inside an async-defun/async-lambda or at top level, and
;; sleep has to be callable from anywhere (clack's handler `stop` calls it inside a
;; plain defun). %future-force blocks on the module scheduler -- so the wait costs no
;; CPU and other pending tasks still progress, unlike the Preview 1 clock spin, which
;; exists only because Preview 1 has no timer to force. This is the same synchronous /
;; asynchronous split sockets.lisp's tcp-* surface uses (.kb/tcp-sockets.md).
;;
;; Being a defun is also what makes `#'sleep` work: no built-in wrapper is injected for
;; a name the program defines. The positive guard keeps a zero duration from costing a
;; host round trip, and matches the interpreter/JVM lowering exactly.
(defun sleep (%sleep-seconds)
  (let ((%sleep-ms (round (* %sleep-seconds 1000))))
    (if (> %sleep-ms 0)
        (rontolisp::%future-force (rontolisp:wait-for %sleep-ms))
        nil)))
