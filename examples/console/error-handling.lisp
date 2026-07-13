;;;; Error handling: typed conditions, handler-case and unwind-protect.
;;;; A tiny bank account that signals a typed `insufficient-funds` error:
;;;; `define-condition` with slots, `:reader` accessors and a `:report` lambda
;;;; (the message an uncaught error prints); `(error 'type :initarg value ...)`
;;;; designators; `handler-case` dispatching by class hierarchy with a
;;;; `:no-error` clause; `ignore-errors`; `unwind-protect` running its cleanup
;;;; on the error path (the audit log records refused withdrawals too); a
;;;; non-fatal `signal` that returns nil when no handler is established; and
;;;; `typecase` / `with-slots` over condition objects.
;;;;
;;;; Runs on every backend except --no-gc; the WASM output uses the wasm
;;;; exception-handling proposal, so wasmtime (37+) needs `-W exceptions=y`.
;;;;
;;;; Run:
;;;;   rontolisp examples/console/error-handling.lisp
;;;;   rontolisp examples/console/error-handling.lisp -o Bank.class && java Bank
;;;;   rontolisp examples/console/error-handling.lisp -o bank.wasm && wasmtime run -W gc -W exceptions=y bank.wasm

;;; The condition hierarchy: error > account-error > insufficient-funds.
;;; account-error exists so a handler can catch every account problem at once.
(define-condition account-error (error) ())

(define-condition insufficient-funds (account-error)
  ((requested :initarg :requested :reader requested-amount)
   (balance :initarg :balance :reader available-balance))
  ;; The :report lambda renders the message an UNCAUGHT error would print,
  ;; e.g. "Error: cannot withdraw 200: only 70 available".
  (:report (lambda (c s)
             (format s "cannot withdraw ~a: only ~a available"
                     (requested-amount c) (available-balance c)))))

(defvar *balance* 100)
(defvar *audit-log* nil)

(defun withdraw (amount)
  "Withdraw AMOUNT or signal a typed insufficient-funds error."
  (when (> amount *balance*)
    (error 'insufficient-funds :requested amount :balance *balance*))
  (setq *balance* (- *balance* amount))
  *balance*)

(defun audited-withdraw (amount)
  "Record every attempt -- unwind-protect runs the cleanup on success AND
when `withdraw` signals, so refused withdrawals reach the audit log too."
  (unwind-protect
      (withdraw amount)
    (setq *audit-log* (cons amount *audit-log*))))

(defun try-withdraw (amount)
  "handler-case: catch by the concrete type and read its slots; :no-error
runs on normal completion with the protected form's value."
  (format t "withdraw ~a:~%" amount)
  (handler-case (audited-withdraw amount)
    (insufficient-funds (e)
      (format t "  refused: wanted ~a but only ~a available~%"
              (requested-amount e) (available-balance e)))
    (:no-error (balance)
      (format t "  ok, new balance ~a~%" balance))))

(format t "Error handling: conditions + handler-case + unwind-protect~%~%")

(try-withdraw 30)                       ; succeeds
(try-withdraw 200)                      ; refused, but still audited
(try-withdraw 60)                       ; succeeds again

;; ignore-errors = handler-case sugar: nil instead of an unwind.
(format t "ignore-errors on withdraw 999 -> ~a~%"
        (ignore-errors (audited-withdraw 999)))

;; The cleanups ran on every path: the refused 200 and 999 are logged too.
(format t "audit log (newest first): ~a~%" *audit-log*)
(format t "balance is still ~a~%~%" *balance*)

;; A clause naming the PARENT type catches the subtype too.
(format t "caught by the parent type -> ~a~%"
        (handler-case (error 'insufficient-funds :requested 1 :balance 0)
          (account-error (e) :caught-as-account-error)))

;; signal is non-fatal: no established handler means it just returns nil...
(format t "signal without a handler -> ~a~%" (signal "balance is getting low"))

;; ...but handler-case catches a raised signal like any other condition.
(format t "signal with a handler    -> ~a~%"
        (handler-case (progn (signal "balance is getting low") :not-reached)
          (condition (c) :noticed)))

;; Condition objects are ordinary values: typecase dispatches on their class
;; hierarchy and with-slots reads their slots (no signaling involved).
(let ((c (make-condition 'insufficient-funds :requested 5 :balance 1)))
  (format t "typecase classifies it as ~a~%"
          (typecase c
            (warning 'some-warning)
            (account-error 'an-account-error)
            (error 'some-other-error)))
  (with-slots (requested balance) c
    (format t "with-slots reads: requested=~a balance=~a~%" requested balance)))
