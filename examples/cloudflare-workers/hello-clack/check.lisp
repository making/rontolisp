;;; Drive worker.lisp without Cloudflare, on any backend.
;;;
;;; The Worker's entry point is a WASM export, but what sits under it is not:
;;; `dispatch` -- a JSON request string in, a JSON response string out, over the
;;; application clackup stored -- is an ordinary function of the handler
;;; backend, and exactly what the synthesized export calls.
;;;
;;; The two lines before the first --> are upstream clack's. On a Worker
;;; (--no-wasi) they go to a discarding stdout; here they do not.

(load "worker.lisp")

(defun try (target)
  (let ((request
         (rontolisp:json-stringify
          (rontolisp:plist-hash-table
           (list :method "GET"
                 :target target
                 :headers (rontolisp:plist-hash-table
                           (list :host "example.com")))))))
    (format t "~&--> ~a~%" target)
    (format t "<-- ~a~%" (clack.handler.reactor:dispatch request))))

(try "/")
(try "/anything")
