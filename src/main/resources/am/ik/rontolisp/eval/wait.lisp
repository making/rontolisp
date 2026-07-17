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
(rontolisp:wit-import "clocks.wit" :interface "wasi:clocks/monotonic-clock@0.3.0" :package %mono-clock)

(defun rontolisp:wait-for (%wait-for-ms)
  ;; The host timer takes nanoseconds (duration = u64); the Lisp surface takes
  ;; non-negative integer milliseconds, like the interpreter/JVM built-in.
  (if (if (integerp %wait-for-ms) (>= %wait-for-ms 0) nil)
      (%mono-clock:wait-for (* %wait-for-ms 1000000))
      (error "rontolisp:wait-for expects a non-negative integer (milliseconds)")))
