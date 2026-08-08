;;; check.lisp -- drive worker.lisp without Cloudflare, on any backend.
;;;
;;; Same shape as ../hello-clack/check.lisp (the notes are there): the WASM
;;; export calls `clack.handler.cloudflare-workers:dispatch`, an ordinary
;;; function, so the whole Worker -- routes included -- runs here too.
;;;
;;;   rontolisp examples/cloudflare-workers/hello-tiny-routes/check.lisp
;;;
;;; The middle probes bind ":name"; the last declines into the catch-all 404.

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
(try "/hello/rontolisp")
(try "/hello/world")
(try "/anything")
