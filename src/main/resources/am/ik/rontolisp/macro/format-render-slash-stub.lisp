;;; The stand-in for the ~/name/ arm (format-render-slash.lisp) in a compiled
;;; program none of whose control strings spells that directive.
;;;
;;; The arm resolves a function out of the control string at run time, and a
;;; program carrying it therefore keeps every function dispatchable for
;;; --optimize (.kb/optimize-dead-code-elimination.md). A control string is
;;; runtime data, so "does this program ever render a ~/name/" cannot be
;;; answered from the source; the compile answers the question it CAN -- does any
;;; control string this compile sees spell one -- and this stub is what a program
;;; that spells none gets instead.
;;;
;;; It signals rather than rendering the directive as text: the renderer's usual
;;; "malformed control renders as text" rule is about bad input to a working
;;; renderer, while this is a capability the artifact does not contain, and
;;; quietly dropping a report's payload gives the user nothing to search for.
;;; Same policy as the other call-time stubs (.kb/clack.md).
;;;
;;; The message must not contain a TILDE. A signalled condition carries the
;;; rendered text in its format-control, and printing the condition renders that
;;; text AGAIN as a control string (%format-condition, .kb/error-handling.md) --
;;; so spelling the directive here would make reporting this error re-enter this
;;; very stub, outside whatever handler-case caught the first one.
(defun %fmt-user-function (ctrl end all out pos i colon at)
  (error "format: this program was compiled without the tilde-slash (call a named function) arm of the runtime renderer, because no control string the compile could see spelled that directive. Compile with --dynamic to keep it."))
