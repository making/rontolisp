;;; demo.lisp -- drive worker.lisp without Cloudflare.
;;;
;;; The Worker's entry point is a WASM export, which only the WASM backends
;;; have; what sits under it does not. `dispatch` -- a JSON request string in, a
;;; JSON response string out, over the application clackup stored -- is an
;;; ordinary function of the handler backend, and it is exactly what the
;;; synthesized export calls. So the whole Worker runs on the interpreter:
;;;
;;;   rontolisp examples/cloudflare-workers/hello-clack/demo.lisp
;;;
;;; and identically on the JVM and the WASM backends.
;;;
;;; The two lines before the first --> are upstream clack's: clackup announces
;;; the server it is about to start, and clack.handler:run announces debug mode.
;;; On a Worker (--no-wasi) they go to a discarding stdout; here they do not.

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
    (format t "<-- ~a~%" (clack.handler.cloudflare-workers:dispatch request))))

(try "/")
(try "/anything")
