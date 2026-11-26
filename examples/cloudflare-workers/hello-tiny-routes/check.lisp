;;; Drive worker.lisp without Cloudflare, on any backend (../hello-clack/check.lisp
;;; has the notes). The middle probes bind ":name"; the last declines into the
;;; catch-all 404.

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
(try "/hello/world")
(try "/anything")
