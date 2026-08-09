;;; check.lisp -- drive worker.lisp without Cloudflare, on any backend.
;;;
;;; Same shape as ../hello-clack/check.lisp (the notes are there): the WASM
;;; export calls `clack.handler.reactor:dispatch`, an ordinary function, so
;;; the whole Worker -- routes included -- runs here too.
;;;
;;;   rontolisp examples/cloudflare-workers/hello-ningle/check.lisp
;;;
;;; The middle probe binds ":name"; the last matches no rule and so reaches the
;;; not-found METHOD, which is where this Worker differs from the tiny-routes
;;; one -- there the 404 is the last route in the list.

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
(try "/hello/rontolisp")
(try "/anything")
